package org.coldis.library.test.service.jms;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.ObjectHelper;
import org.coldis.library.helper.RandomHelper;
import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.client.generator.ServiceClientOperation;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.jms.JmsMessage;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringValueResolver;

/**
 * Enhanced message converter test service.
  */
@Service
@SuppressWarnings({ "rawtypes", "unchecked" })
public class EnhancedMessageConverterTestServiceClient implements ApplicationContextAware, EmbeddedValueResolverAware {

	/** Application context. */
	private ApplicationContext applicationContext;

	/** Value resolver. */
	private StringValueResolver valueResolver;

	/**
	 * Fixed endpoint.
	 */
	private String fixedEndpoint;

	/**
	 * Endpoint bean.
	 */
	private Object endpointBean;

	/**
	 * Endpoint bean property.
	 */
	private String endpointBeanProperty = "endpoint";
	
	/** 
	 * Service path.
	 */
	@Value("")
	private String servicePath;

	/**
	 * Always-sync.
	 */
	@Value("${org.coldis.library.service-client.always-sync:false}")
	private Boolean alwaysSync;

	/**
	 * JMS template.
	 */
	@Autowired(required = false)
	private JmsTemplate jmsTemplate;

	/**
	 * JMS template helper.
	 */
	@Autowired(required = false)
	private JmsTemplateHelper jmsTemplateHelper;

	/**
	 * Service client.
	 */
	@Autowired
	@Qualifier(value = "restServiceClient")
	private GenericRestServiceClient serviceClient;

	/**
	 * No arguments constructor.
	 */
	public EnhancedMessageConverterTestServiceClient() {
		super();
	}

	/**
	 * @see ApplicationContextAware#
	 *     setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(final ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}


	/**
	 * @see org.springframework.context.EmbeddedValueResolverAware#
	 *      setEmbeddedValueResolver(org.springframework.util.StringValueResolver)
	 */
	@Override
	public void setEmbeddedValueResolver(final StringValueResolver resolver) {
		valueResolver = resolver;
	}

	/**
	 * Gets the fixed endpoint.
	 * @return The fixed endpoint.
	 */
	private String getFixedEndpoint() {
		this.fixedEndpoint = (this.fixedEndpoint == null ? this.valueResolver.resolveStringValue("http://localhost:${local.server.port}/enhanced-message-converter-service") : this.fixedEndpoint);
		this.fixedEndpoint = (this.fixedEndpoint == null ? "" : this.fixedEndpoint);
		return this.fixedEndpoint;
	}

	/**
	 * Gets the endpoint bean.
	 * @return The endpoint bean.
	 */
	private Object getEndpointBean() {
		this.endpointBean = (this.endpointBean == null && StringUtils.isEmpty(this.getFixedEndpoint()) ? this.applicationContext.getBean("") : this.endpointBean);
		return this.endpointBean;
	}

	/**
	 * Gets the dynamic endpoint.
	 * @return The dynamic endpoint.
	 */
	private String getActualEndpoint() {
		String endpoint = this.getFixedEndpoint();
		final Object endpointBean = this.getEndpointBean();
		if (endpointBean != null && StringUtils.isNotBlank(this.endpointBeanProperty)) {
			endpoint = (String) ReflectionHelper.getAttribute(endpointBean, this.endpointBeanProperty);
			endpoint = (endpoint != null && endpoint.contains("${") ? valueResolver.resolveStringValue(endpoint) : endpoint);
		}
		return endpoint;
	}

	/**
	 * Gets all available endpoints.
	 * @return All available endpoints.
	 */
	private List<String> getEndpoints() {
		String endpoints = this.getActualEndpoint();
		return (endpoints == null ? null : List.of(endpoints.split(",")));
	}

	/**
	 * Gets one endpoint (balanced).
	 * @return One endpoint (balanced).
	 */
	private String getEndpoint() {
		List<String> endpoints = this.getEndpoints();
		return (CollectionUtils.isEmpty(endpoints) ? "" : endpoints.get(RandomHelper.getPositiveRandomLong((long) (endpoints.size())).intValue()));
	}
	
	/**
	 * Gets the service path.
	 * @return The service path.
	 */
	private String getPath() {
		String actualPath = (this.servicePath == null ? "" : this.servicePath);
		actualPath = (StringUtils.isBlank(actualPath) || actualPath.startsWith("/") ? actualPath : "/" + actualPath);
		return actualPath;
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("send-message")
	private String endpointPath1;

	/**
	 *Sends a message with header, parameters and query parameters. 
	 * @throws BusinessException Any expected errors.
	 */
	
	public void sendMessage(
java.lang.String queue,
org.coldis.library.test.service.jms.DtoTestObjectDto data,
java.lang.Long testJmsAttr1,
java.lang.Long testJmsAttr2,
java.lang.Long sessionAttr3,
java.lang.Long sessionAttr4,
java.lang.Long sessionAttr5,
java.lang.Long testJmsAttr6,
java.lang.Long sessionAttrN
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath1;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder url = new StringBuilder(this.getEndpoint() + this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.POST;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<?> returnType =
				new ParameterizedTypeReference<Void>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// If the parameter is an array.
		if (queue != null && queue.getClass().isArray()) {
			// For each item.
			java.util.List queues = java.util.Arrays.asList(queue);
			for (Integer parameterItemIndex = 0; parameterItemIndex < queues.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("queue" + parameterItemIndex, queues.get(parameterItemIndex));
				url.append("queue={queue" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (queue != null && java.lang.Iterable.class.isAssignableFrom(queue.getClass())) {
			// For each item.
			java.util.Iterator queues = ((java.lang.Iterable)(java.lang.Object) queue).iterator();
			for (Integer parameterItemIndex = 0; queues.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("queue" + parameterItemIndex, queues.next());
				url.append("queue={queue" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (queue != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("queue", queue);
			url.append("queue={queue}&");
		}
		// Sets the operation body.
		body = data;
		if (testJmsAttr1 != null) {
			// Adds the header to the map.
			GenericRestServiceClient.addHeaders(headers, false, "testJmsAttr1", ((String[])(java.util.Collection.class.isAssignableFrom(testJmsAttr1.getClass()) ?
			((java.util.Collection)(java.lang.Object)testJmsAttr1).stream().map(Objects::toString).toArray() :
			List.of(testJmsAttr1.toString()).toArray(new String[] {}))));
		}
		// If the parameter is an array.
		if (testJmsAttr2 != null && testJmsAttr2.getClass().isArray()) {
			// For each item.
			java.util.List testJmsAttr2s = java.util.Arrays.asList(testJmsAttr2);
			for (Integer parameterItemIndex = 0; parameterItemIndex < testJmsAttr2s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("testJmsAttr2" + parameterItemIndex, testJmsAttr2s.get(parameterItemIndex));
				url.append("testJmsAttr2={testJmsAttr2" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (testJmsAttr2 != null && java.lang.Iterable.class.isAssignableFrom(testJmsAttr2.getClass())) {
			// For each item.
			java.util.Iterator testJmsAttr2s = ((java.lang.Iterable)(java.lang.Object) testJmsAttr2).iterator();
			for (Integer parameterItemIndex = 0; testJmsAttr2s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("testJmsAttr2" + parameterItemIndex, testJmsAttr2s.next());
				url.append("testJmsAttr2={testJmsAttr2" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (testJmsAttr2 != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("testJmsAttr2", testJmsAttr2);
			url.append("testJmsAttr2={testJmsAttr2}&");
		}
		// If the parameter is an array.
		if (sessionAttr3 != null && sessionAttr3.getClass().isArray()) {
			// For each item.
			java.util.List sessionAttr3s = java.util.Arrays.asList(sessionAttr3);
			for (Integer parameterItemIndex = 0; parameterItemIndex < sessionAttr3s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("sessionAttr3" + parameterItemIndex, sessionAttr3s.get(parameterItemIndex));
				url.append("sessionAttr3={sessionAttr3" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (sessionAttr3 != null && java.lang.Iterable.class.isAssignableFrom(sessionAttr3.getClass())) {
			// For each item.
			java.util.Iterator sessionAttr3s = ((java.lang.Iterable)(java.lang.Object) sessionAttr3).iterator();
			for (Integer parameterItemIndex = 0; sessionAttr3s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("sessionAttr3" + parameterItemIndex, sessionAttr3s.next());
				url.append("sessionAttr3={sessionAttr3" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (sessionAttr3 != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("sessionAttr3", sessionAttr3);
			url.append("sessionAttr3={sessionAttr3}&");
		}
		// If the parameter is an array.
		if (sessionAttr4 != null && sessionAttr4.getClass().isArray()) {
			// For each item.
			java.util.List sessionAttr4s = java.util.Arrays.asList(sessionAttr4);
			for (Integer parameterItemIndex = 0; parameterItemIndex < sessionAttr4s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("sessionAttr4" + parameterItemIndex, sessionAttr4s.get(parameterItemIndex));
				url.append("sessionAttr4={sessionAttr4" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (sessionAttr4 != null && java.lang.Iterable.class.isAssignableFrom(sessionAttr4.getClass())) {
			// For each item.
			java.util.Iterator sessionAttr4s = ((java.lang.Iterable)(java.lang.Object) sessionAttr4).iterator();
			for (Integer parameterItemIndex = 0; sessionAttr4s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("sessionAttr4" + parameterItemIndex, sessionAttr4s.next());
				url.append("sessionAttr4={sessionAttr4" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (sessionAttr4 != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("sessionAttr4", sessionAttr4);
			url.append("sessionAttr4={sessionAttr4}&");
		}
		// If the parameter is an array.
		if (sessionAttr5 != null && sessionAttr5.getClass().isArray()) {
			// For each item.
			java.util.List sessionAttr5s = java.util.Arrays.asList(sessionAttr5);
			for (Integer parameterItemIndex = 0; parameterItemIndex < sessionAttr5s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("sessionAttr5" + parameterItemIndex, sessionAttr5s.get(parameterItemIndex));
				url.append("sessionAttr5={sessionAttr5" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (sessionAttr5 != null && java.lang.Iterable.class.isAssignableFrom(sessionAttr5.getClass())) {
			// For each item.
			java.util.Iterator sessionAttr5s = ((java.lang.Iterable)(java.lang.Object) sessionAttr5).iterator();
			for (Integer parameterItemIndex = 0; sessionAttr5s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("sessionAttr5" + parameterItemIndex, sessionAttr5s.next());
				url.append("sessionAttr5={sessionAttr5" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (sessionAttr5 != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("sessionAttr5", sessionAttr5);
			url.append("sessionAttr5={sessionAttr5}&");
		}
		// If the parameter is an array.
		if (testJmsAttr6 != null && testJmsAttr6.getClass().isArray()) {
			// For each item.
			java.util.List testJmsAttr6s = java.util.Arrays.asList(testJmsAttr6);
			for (Integer parameterItemIndex = 0; parameterItemIndex < testJmsAttr6s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("testJmsAttr6" + parameterItemIndex, testJmsAttr6s.get(parameterItemIndex));
				url.append("testJmsAttr6={testJmsAttr6" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (testJmsAttr6 != null && java.lang.Iterable.class.isAssignableFrom(testJmsAttr6.getClass())) {
			// For each item.
			java.util.Iterator testJmsAttr6s = ((java.lang.Iterable)(java.lang.Object) testJmsAttr6).iterator();
			for (Integer parameterItemIndex = 0; testJmsAttr6s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("testJmsAttr6" + parameterItemIndex, testJmsAttr6s.next());
				url.append("testJmsAttr6={testJmsAttr6" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (testJmsAttr6 != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("testJmsAttr6", testJmsAttr6);
			url.append("testJmsAttr6={testJmsAttr6}&");
		}
		// If the parameter is an array.
		if (sessionAttrN != null && sessionAttrN.getClass().isArray()) {
			// For each item.
			java.util.List sessionAttrNs = java.util.Arrays.asList(sessionAttrN);
			for (Integer parameterItemIndex = 0; parameterItemIndex < sessionAttrNs.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("sessionAttrN" + parameterItemIndex, sessionAttrNs.get(parameterItemIndex));
				url.append("sessionAttrN={sessionAttrN" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (sessionAttrN != null && java.lang.Iterable.class.isAssignableFrom(sessionAttrN.getClass())) {
			// For each item.
			java.util.Iterator sessionAttrNs = ((java.lang.Iterable)(java.lang.Object) sessionAttrN).iterator();
			for (Integer parameterItemIndex = 0; sessionAttrNs.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("sessionAttrN" + parameterItemIndex, sessionAttrNs.next());
				url.append("sessionAttrN={sessionAttrN" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (sessionAttrN != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("sessionAttrN", sessionAttrN);
			url.append("sessionAttrN={sessionAttrN}&");
		}
		// Executes the operation and returns the response.
this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);
				
	}
	

}