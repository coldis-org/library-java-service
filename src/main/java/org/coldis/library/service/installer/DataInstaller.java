package org.coldis.library.service.installer;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.coldis.library.model.view.ModelView;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringValueResolver;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Data installer.
 */
@RestController
@RequestMapping(path = "${org.coldis.configuration.data-installer}")
@ConditionalOnProperty(
		name = "org.coldis.configuration.data-installer-enabled",
		havingValue = "true",
		matchIfMissing = true
)
public class DataInstaller implements ApplicationListener<ApplicationReadyEvent>, EmbeddedValueResolverAware {

	/**
	 * Placeholder in a search operation path template ("{property}"): replaced with that property of
	 * the data object.
	 */
	private static final Pattern SEARCH_PATH_PLACEHOLDER = Pattern.compile("\\{([^{}/]+)\\}");


	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(DataInstaller.class);

	/**
	 * Resource pattern.
	 */
	private static final String RESOURCE_PATTERN = "\\$\\{resource:([^\\}]*)\\}";

	/**
	 * Resource pattern.
	 */
	private static final String PROPERTY_PATTERN = "\\$\\{property:([^\\}]*)\\}";

	/**
	 * Mask that replaces credential values in logged data objects.
	 */
	public static final String CREDENTIAL_MASK = "******";

	/**
	 * Pattern (case-insensitive, matched anywhere in the key) for data object keys
	 * whose values are credentials and must never be logged.
	 */
	private static final Pattern CREDENTIAL_KEY_PATTERN = Pattern.compile(
			"password|secret|token|credential|api[-_]?key|private[-_]?key|authorization", Pattern.CASE_INSENSITIVE);

	/**
	 * Rule thread pool.
	 */
	public static ExecutorService THREAD_POOL = Executors.newFixedThreadPool(1);

	/**
	 * Value resolver.
	 */
	private StringValueResolver valueResolver;

	/**
	 * Data to be installed.
	 */
	@Value(value = "classpath*:${org.coldis.configuration.data-installer}/*")
	private String data;

	/**
	 * Data not to be installed.
	 */
	@Value(value = "#{'${org.coldis.configuration.data-installer-ignore}'.split(',')}")
	private List<String> ignoreData;

	/**
	 * Bean factory.
	 */
	@Autowired
	private BeanFactory beans;

	/**
	 * Resource pattern resolver.
	 */
	@Autowired
	private ResourcePatternResolver resourcePatternResolver;

	/**
	 * Object mapper.
	 */
	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * @see org.springframework.context.EmbeddedValueResolverAware#
	 *      setEmbeddedValueResolver(org.springframework.util.StringValueResolver)
	 */
	@Override
	public void setEmbeddedValueResolver(
			final StringValueResolver resolver) {
		this.valueResolver = resolver;
	}

	/**
	 * Sets the thread pool size.
	 *
	 * @param parallelism  Parallelism (activates work stealing pool).
	 * @param corePoolSize Core pool size (activates blocking thread pool).
	 * @param maxPoolSize  Max pool size.
	 * @param queueSize    Queue size.
	 * @param keepAlive    Keep alive.
	 */
	@Autowired
	private void setThreadPoolSize(
			@Value("${org.coldis.library.spring.data-installer.thread.pool.core-size:3}")
			final Integer corePoolSize,
			@Value("${org.coldis.library.spring.data-installer.thread.pool.max-size:5}")
			final Integer maxPoolSize,
			@Value("${org.coldis.library.spring.data-installer.thread.pool.queue-size:1024}")
			final Integer queueSize,
			@Value("${org.coldis.library.spring.data-installer.thread.pool.keep-alive:37}")
			final Integer keepAlive) {
		final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAlive, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(queueSize, true));
		threadPoolExecutor.allowCoreThreadTimeOut(true);
		DataInstaller.THREAD_POOL = threadPoolExecutor;
	}

	/**
	 * Gets the service operation URL with a single address: the first of multiple addresses (as
	 * "http://a,http://b,/path") followed by the path.
	 *
	 * @param  serviceOperationUrl Service operation URL, possibly with multiple addresses.
	 * @return                     The service operation URL with a single address.
	 */
	private String getActualServiceOperationUrl(
			final String serviceOperationUrl) {
		final String[] splitServiceOperationUrl = serviceOperationUrl.split(",");
		return (splitServiceOperationUrl.length > 1) ? splitServiceOperationUrl[0] + splitServiceOperationUrl[splitServiceOperationUrl.length - 1]
				: splitServiceOperationUrl[0];
	}

	/**
	 * Appends the search properties to the search operation URL, as path segments or as query
	 * parameters (by the search strategy).
	 *
	 * @param  actualServiceOperationUrl Search operation URL (resolved).
	 * @param  searchStrategy            Search strategy.
	 * @param  searchProperties          Search properties.
	 * @param  dataObject                Data object.
	 * @return                           The search operation URL with the search properties.
	 */
	private String appendSearchProperties(
			final String actualServiceOperationUrl,
			final DataInstallerSearchStrategy searchStrategy,
			final String[] searchProperties,
			final Map<String, Object> dataObject) {

		// Service operation URL.
		final StringBuffer serviceOperationUrl = new StringBuffer(actualServiceOperationUrl);
		// If the search uses parameter strategy.
		if (DataInstallerSearchStrategy.PARAMETER.equals(searchStrategy)) {
			// Appends the search context.
			serviceOperationUrl.append("?");
		}
		// For each property.
		for (final String searchProperty : searchProperties) {
			// Adds the current field to the parameters string.
			// Depending on the search strategy.
			switch (searchStrategy) {
				// For path strategy.
				case PATH:
					// Adds the property to the URL.
					serviceOperationUrl.append("/");
					serviceOperationUrl.append(dataObject.get(searchProperty));
					break;
				// For query parameter strategy.
				case PARAMETER:
					// Adds the property to the URL.
					serviceOperationUrl.append(searchProperty + "=" + dataObject.get(searchProperty) + "&");
					break;
			}
		}
		// Returns the search operation URL.
		return serviceOperationUrl.toString();
	}

	/**
	 * Gets the value of a search path placeholder: the data object's property. A placeholder naming a
	 * property the data object does not have (or has as null) fails the data set (a search that always
	 * misses would create every object again over the existing ones).
	 *
	 * @param  propertyName Property named by the placeholder.
	 * @param  dataObject   Data object.
	 * @return              The property value.
	 */
	private String getSearchPathValue(
			final String propertyName,
			final Map<String, Object> dataObject) {
		if (dataObject.get(propertyName) == null) {
			throw new IllegalArgumentException(
					"Search operation path placeholder '{" + propertyName + "}' names no property (or a null one) of the data object.");
		}
		return Objects.toString(dataObject.get(propertyName));
	}

	/**
	 * Gets the search operation URL. A search operation path with placeholders ("{property}", e.g.
	 * "{code}/code") is a template: each placeholder is replaced with that property of the data object,
	 * and the search properties are not appended. Otherwise, the search properties are appended to the
	 * URL (with the search operation path, if any), as path segments or as query parameters (by the
	 * search strategy). Only the search operation path can be a template, never the service URL.
	 *
	 * @param  serviceOperationBaseUrl Service operation base URL.
	 * @param  searchOperationPath     Search operation path (empty for none).
	 * @param  searchStrategy          Search strategy.
	 * @param  searchProperties        Search properties.
	 * @param  dataObject              Data object.
	 * @return                         The search operation URL.
	 */
	private String getSearchOperationUrl(
			final String serviceOperationBaseUrl,
			final String searchOperationPath,
			final DataInstallerSearchStrategy searchStrategy,
			final String[] searchProperties,
			final Map<String, Object> dataObject) {
		final String actualSearchOperationPath = Objects.toString(searchOperationPath, "");
		final Matcher searchPathPlaceholder = DataInstaller.SEARCH_PATH_PLACEHOLDER.matcher(actualSearchOperationPath);
		final String searchOperationUrl;
		// Template: the placeholders are filled, nothing is appended.
		if (searchPathPlaceholder.find()) {
			searchOperationUrl = this.getActualServiceOperationUrl(serviceOperationBaseUrl) + "/"
					+ searchPathPlaceholder.replaceAll(placeholder -> Matcher.quoteReplacement(this.getSearchPathValue(placeholder.group(1), dataObject)));
		}
		// Otherwise, the search properties are appended.
		else {
			final String serviceOperationUrl = serviceOperationBaseUrl
					+ (StringUtils.isBlank(actualSearchOperationPath) ? "" : "/" + actualSearchOperationPath);
			searchOperationUrl = this.appendSearchProperties(this.getActualServiceOperationUrl(serviceOperationUrl), searchStrategy, searchProperties,
					dataObject);
		}
		return searchOperationUrl;
	}

	/**
	 * Gets resource content.
	 *
	 * @param  resourceLocation Resource location.
	 * @return                  The resource content.
	 */
	private String getResourceContent(
			final String resourceLocation) {
		// Resource content.
		String resourceContent = "";
		// Tries to get the resource content.
		try {
			// Tries to get the resource content.
			resourceContent = StreamUtils.copyToString(this.resourcePatternResolver.getResource(resourceLocation).getInputStream(), Charset.forName("UTF-8"));
			// Escapes the content and regular expression group reference.
			resourceContent = StringEscapeUtils.escapeJava(StringEscapeUtils.escapeJava(resourceContent));
			resourceContent = resourceContent.replace("$", "\\$");
		}
		// If the resource content cannot be retrieved.
		catch (final Exception exception) {
			// Logs it.
			DataInstaller.LOGGER.error("Resource content could not be retrieved for '" + resourceLocation + "'.", exception);
		}
		// Returns the resource content.
		return resourceContent;
	}

	/**
	 * Gets the REST service client.
	 *
	 * @param  name Service client bean name.
	 * @return      The REST service client.
	 */
	private GenericRestServiceClient getGenericRestServiceClient(
			final String name) {
		return StringUtils.isEmpty(name) ? this.beans.getBean("restServiceClient", GenericRestServiceClient.class)
				: this.beans.getBean(name, GenericRestServiceClient.class);
	}

	/**
	 * Masks credentials in a data object, recursively. Every value (including
	 * nested maps and lists) whose key looks like a credential (see
	 * {@link #CREDENTIAL_KEY_PATTERN}) is replaced by {@link #CREDENTIAL_MASK}.
	 * Other values are kept, so the log still tells which object it is. The
	 * original object is not changed.
	 *
	 * @param  value Value (map, collection, array or plain value).
	 * @return       A copy of the value with credentials masked.
	 */
	private static Object maskCredentials(
			final Object value) {
		Object maskedValue = value;
		// If the value is a map, masks credential keys and goes into the other values.
		if (value instanceof Map) {
			final Map<Object, Object> maskedMap = new LinkedHashMap<>();
			for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				final boolean credentialKey = (entry.getKey() != null) && DataInstaller.CREDENTIAL_KEY_PATTERN.matcher(entry.getKey().toString()).find();
				maskedMap.put(entry.getKey(), credentialKey ? (entry.getValue() == null ? null : DataInstaller.CREDENTIAL_MASK)
						: DataInstaller.maskCredentials(entry.getValue()));
			}
			maskedValue = maskedMap;
		}
		// If the value is a collection, masks each item.
		else if (value instanceof Collection) {
			final List<Object> maskedList = new ArrayList<>();
			for (final Object item : (Collection<?>) value) {
				maskedList.add(DataInstaller.maskCredentials(item));
			}
			maskedValue = maskedList;
		}
		// If the value is an object array, masks each item.
		else if (value instanceof Object[]) {
			final List<Object> maskedList = new ArrayList<>();
			for (final Object item : (Object[]) value) {
				maskedList.add(DataInstaller.maskCredentials(item));
			}
			maskedValue = maskedList;
		}
		return maskedValue;
	}

	/**
	 * Serializes a data object for logging, with its credentials masked.
	 *
	 * @param  dataObject Data object.
	 * @return            The serialized data object, safe to log.
	 */
	private String serializeForLog(
			final Map<String, Object> dataObject) {
		return ObjectMapperHelper.serialize(this.objectMapper, DataInstaller.maskCredentials(dataObject), ModelView.Public.class, true);
	}

	/**
	 * Install data set.
	 *
	 * @param dataSetName     Data set name.
	 * @param dataSetMetadata Data set metadata.
	 */
	private void installDataSet(
			final String dataSetName,
			final DataInstallationMetadata dataSetMetadata) {
		try {
			// Log information.
			int createdObjects = 0;
			int updatedObjects = 0;
			int failedObjects = 0;
			DataInstaller.LOGGER.info("Installing data for data set '" + dataSetName + "'.");
			// Gets the service client bean to be used.
			final GenericRestServiceClient serviceClient = this.getGenericRestServiceClient(dataSetMetadata.getServiceClientBean());
			// Search URLs, built for every object before any is installed: a bad search path template fails the
			// data set, and nothing is installed from it.
			final List<Map<String, Object>> dataObjects = dataSetMetadata.getData();
			final List<String> searchOperationUrls = dataObjects.stream()
					.map(dataObject -> this.getSearchOperationUrl(dataSetMetadata.getServiceOperationUrl(), dataSetMetadata.getSearchOperationPath(),
							dataSetMetadata.getSearchPropertiesStrategy(), dataSetMetadata.getSearchProperties(), dataObject))
					.toList();
			// For each data object to be installed.
			for (int objectIndex = 0; objectIndex < dataObjects.size(); objectIndex++) {
				final Map<String, Object> dataObject = dataObjects.get(objectIndex);
				final String searchOperationUrl = searchOperationUrls.get(objectIndex);
				// Retrieved object.
				Map<String, Object> existentDataObject = null;
				// Tries to get existent data.
				try {
					existentDataObject = (serviceClient
							.executeOperation(searchOperationUrl, HttpMethod.GET, null, null, null, new ParameterizedTypeReference<Map<String, Object>>() {})
							.getBody());
				}
				// If the object does not exist.
				catch (final Exception exception) {
					// Logs it.
					DataInstaller.LOGGER.debug("Object '" + this.serializeForLog(dataObject) + "' does not exist.", exception);
				}
				// If the object does not exist.
				if (existentDataObject == null) {
					// Tries to create the object.
					try {
						serviceClient.executeOperation(this.getActualServiceOperationUrl(dataSetMetadata.getServiceOperationUrl()), HttpMethod.POST, null, dataObject, null,
								new ParameterizedTypeReference<Void>() {});
						DataInstaller.LOGGER.debug("Object '" + this.serializeForLog(dataObject) + "' created.");
						createdObjects++;
					}
					// If the object cannot be created.
					catch (final Exception exception) {
						// Logs it.
						DataInstaller.LOGGER.error("Object '" + this.serializeForLog(dataObject) + "' could not be created.", exception);
						failedObjects++;
					}
				}
				// If the object does not exist.
				else {
					// If data should be updated.
					if (!dataSetMetadata.getCreateOnly()) {
						// Tries to update the object.
						try {
							serviceClient.executeOperation(
									this.getSearchOperationUrl(dataSetMetadata.getServiceOperationUrl(), "", dataSetMetadata.getIdPropertiesStrategy(),
											dataSetMetadata.getIdProperties(), existentDataObject),
									HttpMethod.PUT, null, dataObject, null, new ParameterizedTypeReference<Void>() {});
							DataInstaller.LOGGER.debug("Object '" + this.serializeForLog(dataObject) + "' updated.");
							updatedObjects++;
						}
						// If the object cannot be updated.
						catch (final Exception exception) {
							// Logs it.
							DataInstaller.LOGGER.error("Object '" + this.serializeForLog(dataObject) + "' could not be updated.", exception);
							failedObjects++;
						}
					}
				}
			}
			// Logs the model data installation.
			DataInstaller.LOGGER.info("Data installer finished for '" + dataSetName + "' (URL: '" + dataSetMetadata.getServiceOperationUrl() + "') with '"
					+ createdObjects + "' created, '" + updatedObjects + "' updated and '" + failedObjects + "' objects with errors.");
		}
		// If the data sets installation fails.
		catch (final Exception exception) {
			DataInstaller.LOGGER.error("Data installer finished for '" + dataSetName + "'.", exception);
		}
	}

	/**
	 * Processes and installs a single data set resource.
	 *
	 * @param dataSet The resource to install.
	 */
	private void installResource(
			final Resource dataSet) throws Exception {
		// Gets the data set content.
		String dataSetContent = StreamUtils.copyToString(dataSet.getInputStream(), Charset.forName("UTF-8"));
		dataSetContent = Pattern.compile(DataInstaller.RESOURCE_PATTERN).matcher(dataSetContent)
				.replaceAll(result -> this.getResourceContent(result.group(1)));
		dataSetContent = Pattern.compile(DataInstaller.PROPERTY_PATTERN).matcher(dataSetContent)
				.replaceAll(result -> this.valueResolver.resolveStringValue("${" + result.group(1) + "}"));
		// Gets the installation metadata.
		final String dataSetName = dataSet.getFilename();
		final DataInstallationMetadata dataSetMetadata = ObjectMapperHelper.deserialize(this.objectMapper, dataSetContent,
				DataInstallationMetadata.class, false);
		// If the data should be ignored.
		if (CollectionUtils.isNotEmpty(this.ignoreData)
				&& this.ignoreData.stream().anyMatch(currentIgnoredData -> dataSetName.matches(currentIgnoredData))) {
			// Logs it.
			DataInstaller.LOGGER.info("Ignoring data installation for data set '" + dataSetName + "'.");
		}
		// If the data should not be ignored.
		else {
			if (dataSetMetadata.getAsynchronously()) {
				CompletableFuture.runAsync(new Runnable() {

					@Override
					public void run() {
						DataInstaller.this.installDataSet(dataSetName, dataSetMetadata);
					}
				}, DataInstaller.THREAD_POOL);
			}
			else {
				// Installs the data set.
				this.installDataSet(dataSetName, dataSetMetadata);
			}
		}
	}

	/**
	 * Installs data.
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(method = { RequestMethod.POST, RequestMethod.GET })
	public void install() {
		// Tries to install data.
		try {
			DataInstaller.LOGGER.info("Starting data installer.");
			// Gets the data sets to be installed.
			final Resource[] dataSets = this.resourcePatternResolver.getResources(this.data);
			// Sorts the data sets by name.
			Arrays.sort(dataSets, new Comparator<Resource>() {
				/**
				 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
				 */
				@Override
				public final int compare(
						final Resource resource1,
						final Resource resource2) {
					return resource1.getFilename().compareTo(resource2.getFilename());
				}
			});
			// Installs each data set.
			for (final Resource dataSet : dataSets) {
				this.installResource(dataSet);
			}
			DataInstaller.LOGGER.info("Finishing data installer");
		}
		// If the data cannot be installed.
		catch (final Exception exception) {
			// Logs it.
			DataInstaller.LOGGER.error("Data could not be installed.", exception);
		}
	}

	/**
	 * Installs a specific data file by name.
	 *
	 * @param fileName The file name to install.
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(
			path = "/{fileName}",
			method = { RequestMethod.POST, RequestMethod.GET }
	)
	public void install(
			@PathVariable
			final String fileName) {
		// Tries to install data.
		try {
			DataInstaller.LOGGER.info("Starting data installer for file '" + fileName + "'.");
			// Gets the data sets to be installed.
			final Resource[] dataSets = this.resourcePatternResolver.getResources(this.data);
			// Finds the matching data set.
			boolean found = false;
			for (final Resource dataSet : dataSets) {
				if (dataSet.getFilename().equals(fileName)) {
					this.installResource(dataSet);
					found = true;
					break;
				}
			}
			if (!found) {
				DataInstaller.LOGGER.warn("Data file '" + fileName + "' not found.");
			}
			DataInstaller.LOGGER.info("Finishing data installer for file '" + fileName + "'.");
		}
		// If the data cannot be installed.
		catch (final Exception exception) {
			// Logs it.
			DataInstaller.LOGGER.error("Data could not be installed for file '" + fileName + "'.", exception);
		}
	}

	/**
	 * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
	 */
	@Override
	public void onApplicationEvent(
			final ApplicationReadyEvent event) {
		this.install();
	}

}
