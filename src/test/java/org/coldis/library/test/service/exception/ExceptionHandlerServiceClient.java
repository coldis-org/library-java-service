package org.coldis.library.test.service.exception;

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
 * Exception handler service.
  */
@Service
@SuppressWarnings({ "rawtypes", "unchecked" })
public class ExceptionHandlerServiceClient implements ApplicationContextAware, EmbeddedValueResolverAware {

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
	public ExceptionHandlerServiceClient() {
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
		this.fixedEndpoint = (this.fixedEndpoint == null ? this.valueResolver.resolveStringValue("http://localhost:${local.server.port}/exception") : this.fixedEndpoint);
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
	@Value("business")
	private String endpointPath1;

	/**
	 * Test service.

 @param  code              Message code.
 @param  parameters        Parameters.
 @throws BusinessException Exception.

	 * @throws BusinessException Any expected errors.
	 */
	
	public void businessExceptionService(
java.lang.String code,
java.lang.Object[] parameters
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
		if (code != null && code.getClass().isArray()) {
			// For each item.
			java.util.List codes = java.util.Arrays.asList(code);
			for (Integer parameterItemIndex = 0; parameterItemIndex < codes.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("code" + parameterItemIndex, codes.get(parameterItemIndex));
				url.append("code={code" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (code != null && java.lang.Iterable.class.isAssignableFrom(code.getClass())) {
			// For each item.
			java.util.Iterator codes = ((java.lang.Iterable)(java.lang.Object) code).iterator();
			for (Integer parameterItemIndex = 0; codes.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("code" + parameterItemIndex, codes.next());
				url.append("code={code" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (code != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("code", code);
			url.append("code={code}&");
		}
		// If the parameter is an array.
		if (parameters != null && parameters.getClass().isArray()) {
			// For each item.
			java.util.List parameterss = java.util.Arrays.asList(parameters);
			for (Integer parameterItemIndex = 0; parameterItemIndex < parameterss.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("parameters" + parameterItemIndex, parameterss.get(parameterItemIndex));
				url.append("parameters={parameters" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (parameters != null && java.lang.Iterable.class.isAssignableFrom(parameters.getClass())) {
			// For each item.
			java.util.Iterator parameterss = ((java.lang.Iterable)(java.lang.Object) parameters).iterator();
			for (Integer parameterItemIndex = 0; parameterss.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("parameters" + parameterItemIndex, parameterss.next());
				url.append("parameters={parameters" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (parameters != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("parameters", parameters);
			url.append("parameters={parameters}&");
		}
		// Executes the operation and returns the response.
this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("integration")
	private String endpointPath2;

	/**
	 * Test service.

 @param  code                 Message code.
 @param  parameters           Parameters.
 @throws IntegrationException Exception.

	 * @throws BusinessException Any expected errors.
	 */
	
	public void integrationExceptionService(
java.lang.String code,
java.lang.Object[] parameters
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath2;
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
		if (code != null && code.getClass().isArray()) {
			// For each item.
			java.util.List codes = java.util.Arrays.asList(code);
			for (Integer parameterItemIndex = 0; parameterItemIndex < codes.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("code" + parameterItemIndex, codes.get(parameterItemIndex));
				url.append("code={code" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (code != null && java.lang.Iterable.class.isAssignableFrom(code.getClass())) {
			// For each item.
			java.util.Iterator codes = ((java.lang.Iterable)(java.lang.Object) code).iterator();
			for (Integer parameterItemIndex = 0; codes.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("code" + parameterItemIndex, codes.next());
				url.append("code={code" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (code != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("code", code);
			url.append("code={code}&");
		}
		// If the parameter is an array.
		if (parameters != null && parameters.getClass().isArray()) {
			// For each item.
			java.util.List parameterss = java.util.Arrays.asList(parameters);
			for (Integer parameterItemIndex = 0; parameterItemIndex < parameterss.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("parameters" + parameterItemIndex, parameterss.get(parameterItemIndex));
				url.append("parameters={parameters" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (parameters != null && java.lang.Iterable.class.isAssignableFrom(parameters.getClass())) {
			// For each item.
			java.util.Iterator parameterss = ((java.lang.Iterable)(java.lang.Object) parameters).iterator();
			for (Integer parameterItemIndex = 0; parameterss.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("parameters" + parameterItemIndex, parameterss.next());
				url.append("parameters={parameters" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (parameters != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("parameters", parameters);
			url.append("parameters={parameters}&");
		}
		// Executes the operation and returns the response.
this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("constraint-violation")
	private String endpointPath3;

	/**
	 * Test service.

 @param object Test object.

	 * @throws BusinessException Any expected errors.
	 */
	
	public void constraintViolationExceptionService(
org.coldis.library.test.service.exception.ExceptionTestClass object
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath3;
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
		// Sets the operation body.
		body = object;
		// Executes the operation and returns the response.
this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);
				
	}
	

}