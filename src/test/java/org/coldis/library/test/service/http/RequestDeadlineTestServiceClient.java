package org.coldis.library.test.service.http;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.ObjectHelper;
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
 * Request deadline test service: tells what deadline a request got, and calls
 itself through its generated client, as a service calls another one.
  */
@Service
@SuppressWarnings({ "rawtypes", "unchecked" })
public class RequestDeadlineTestServiceClient implements ApplicationContextAware, EmbeddedValueResolverAware {

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
	 * Read timeout of this client's calls, in seconds:
	 * org.coldis.library.service-client.timeout.RequestDeadlineTestServiceClient (none, zero or negative: the template's).
	 * Applies only to templates whose request factory is the library's (ServiceClientRequestFactory).
	 * The key sits under the global timeout's, so in YAML it is written flat (quoted), not nested.
	 */
	@Value("${org.coldis.library.service-client.timeout.RequestDeadlineTestServiceClient:#{null}}")
	private Long readTimeoutSeconds;

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
	 * Sets the service client.
	 * @param serviceClient Service client.
	 */
	public void setServiceClient(final GenericRestServiceClient serviceClient) {
		this.serviceClient = serviceClient;
	}

	/**
	 * No arguments constructor.
	 */
	public RequestDeadlineTestServiceClient() {
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
		this.fixedEndpoint = (this.fixedEndpoint == null ? this.valueResolver.resolveStringValue("http://localhost:${local.server.port}/request-deadline") : this.fixedEndpoint);
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
	 * Gets all available endpoints (the operation fails over them).
	 * @return All available endpoints.
	 */
	private List<String> getEndpoints() {
		String endpoints = this.getActualEndpoint();
		return (endpoints == null ? null : List.of(endpoints.split(",")));
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
	@Value("remaining")
	private String endpointPath1;

	/**
	 * Gets what is left of the request's deadline.

 @return What is left of the request's deadline (milliseconds), or
         {@link #NONE}.

	 * @throws BusinessException Any expected errors.
	 */
	
	public java.lang.String remaining(

			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath1;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder pathAndQuery = new StringBuilder(this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.String> returnType =
				new ParameterizedTypeReference<java.lang.String>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// Executes the operation (failing over the endpoints) and returns the response.
return this.serviceClient.executeOperation(this.getEndpoints(), pathAndQuery.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType, null,
				(this.readTimeoutSeconds == null ? null : java.time.Duration.ofSeconds(this.readTimeoutSeconds)), "RequestDeadlineTestServiceClient").getBody();
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("header")
	private String endpointPath2;

	/**
	 * Gets the deadline header the request came with.

 @param  header Deadline header.
 @return        The deadline header ({@link #NONE} if absent).

	 * @throws BusinessException Any expected errors.
	 */
	
	public java.lang.String header(
java.lang.String header
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath2;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder pathAndQuery = new StringBuilder(this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.String> returnType =
				new ParameterizedTypeReference<java.lang.String>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		if (header != null) {
			// Adds the header to the map.
			GenericRestServiceClient.addHeaders(headers, false, "X-Request-Timeout-Ms", ((String[])(java.util.Collection.class.isAssignableFrom(header.getClass()) ?
			((java.util.Collection)(java.lang.Object)header).stream().map(Objects::toString).toArray() :
			List.of(header.toString()).toArray(new String[] {}))));
		}
		// Executes the operation (failing over the endpoints) and returns the response.
return this.serviceClient.executeOperation(this.getEndpoints(), pathAndQuery.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType, null,
				(this.readTimeoutSeconds == null ? null : java.time.Duration.ofSeconds(this.readTimeoutSeconds)), "RequestDeadlineTestServiceClient").getBody();
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("slow")
	private String endpointPath3;

	/**
	 * Answers after {@link #SLOW_DELAY_MILLIS}.

 @return An answer.

	 * @throws BusinessException Any expected errors.
	 */
	
	public java.lang.String slow(

			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath3;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder pathAndQuery = new StringBuilder(this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.String> returnType =
				new ParameterizedTypeReference<java.lang.String>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// Executes the operation (failing over the endpoints) and returns the response.
return this.serviceClient.executeOperation(this.getEndpoints(), pathAndQuery.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType, null,
				(this.readTimeoutSeconds == null ? null : java.time.Duration.ofSeconds(this.readTimeoutSeconds)), "RequestDeadlineTestServiceClient").getBody();
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("forward-header")
	private String endpointPath4;

	/**
	 * Calls {@link #header(String)} through the generated client.

 @return                      The deadline header the call carried.
 @throws IntegrationException If the call fails.
 @throws BusinessException    If the call fails.

	 * @throws BusinessException Any expected errors.
	 */
	
	public java.lang.String forwardHeader(

			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath4;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder pathAndQuery = new StringBuilder(this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.String> returnType =
				new ParameterizedTypeReference<java.lang.String>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// Executes the operation (failing over the endpoints) and returns the response.
return this.serviceClient.executeOperation(this.getEndpoints(), pathAndQuery.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType, null,
				(this.readTimeoutSeconds == null ? null : java.time.Duration.ofSeconds(this.readTimeoutSeconds)), "RequestDeadlineTestServiceClient").getBody();
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("forward-slow")
	private String endpointPath5;

	/**
	 * Calls {@link #slow()} through the generated client.

 @return                      The answer.
 @throws IntegrationException If the call fails.
 @throws BusinessException    If the call fails.

	 * @throws BusinessException Any expected errors.
	 */
	
	public java.lang.String forwardSlow(

			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath5;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder pathAndQuery = new StringBuilder(this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.String> returnType =
				new ParameterizedTypeReference<java.lang.String>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// Executes the operation (failing over the endpoints) and returns the response.
return this.serviceClient.executeOperation(this.getEndpoints(), pathAndQuery.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType, null,
				(this.readTimeoutSeconds == null ? null : java.time.Duration.ofSeconds(this.readTimeoutSeconds)), "RequestDeadlineTestServiceClient").getBody();
				
	}
	

}