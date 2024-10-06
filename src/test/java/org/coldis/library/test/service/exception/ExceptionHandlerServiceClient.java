package org.coldis.library.test.service.exception;

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
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.jms.JmsMessage;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
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
public class ExceptionHandlerServiceClient implements EmbeddedValueResolverAware {
	
	/**
	 * Value resolver.
	 */
	private StringValueResolver valueResolver;
	
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
@Qualifier(value = "restServiceClient")	private GenericRestServiceClient serviceClient;

	/**
	 * No arguments constructor.
	 */
	public ExceptionHandlerServiceClient() {
		super();
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
		StringBuilder path = new StringBuilder(this.valueResolver
				.resolveStringValue("http://localhost:${local.server.port}/exception" + (StringUtils.isBlank("business") ? "" : "/business") + "?"));
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
				path.append("code={code" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (code != null && java.lang.Iterable.class.isAssignableFrom(code.getClass())) {
			// For each item.
			java.util.Iterator codes = ((java.lang.Iterable)(java.lang.Object) code).iterator();
			for (Integer parameterItemIndex = 0; codes.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("code" + parameterItemIndex, codes.next());
				path.append("code={code" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (code != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("code", code);
			path.append("code={code}&");
		}
		// If the parameter is an array.
		if (parameters != null && parameters.getClass().isArray()) {
			// For each item.
			java.util.List parameterss = java.util.Arrays.asList(parameters);
			for (Integer parameterItemIndex = 0; parameterItemIndex < parameterss.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("parameters" + parameterItemIndex, parameterss.get(parameterItemIndex));
				path.append("parameters={parameters" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (parameters != null && java.lang.Iterable.class.isAssignableFrom(parameters.getClass())) {
			// For each item.
			java.util.Iterator parameterss = ((java.lang.Iterable)(java.lang.Object) parameters).iterator();
			for (Integer parameterItemIndex = 0; parameterss.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("parameters" + parameterItemIndex, parameterss.next());
				path.append("parameters={parameters" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (parameters != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("parameters", parameters);
			path.append("parameters={parameters}&");
		}
		// Executes the operation and returns the response.
this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);
				
	}
	
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
		StringBuilder path = new StringBuilder(this.valueResolver
				.resolveStringValue("http://localhost:${local.server.port}/exception" + (StringUtils.isBlank("integration") ? "" : "/integration") + "?"));
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
				path.append("code={code" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (code != null && java.lang.Iterable.class.isAssignableFrom(code.getClass())) {
			// For each item.
			java.util.Iterator codes = ((java.lang.Iterable)(java.lang.Object) code).iterator();
			for (Integer parameterItemIndex = 0; codes.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("code" + parameterItemIndex, codes.next());
				path.append("code={code" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (code != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("code", code);
			path.append("code={code}&");
		}
		// If the parameter is an array.
		if (parameters != null && parameters.getClass().isArray()) {
			// For each item.
			java.util.List parameterss = java.util.Arrays.asList(parameters);
			for (Integer parameterItemIndex = 0; parameterItemIndex < parameterss.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("parameters" + parameterItemIndex, parameterss.get(parameterItemIndex));
				path.append("parameters={parameters" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (parameters != null && java.lang.Iterable.class.isAssignableFrom(parameters.getClass())) {
			// For each item.
			java.util.Iterator parameterss = ((java.lang.Iterable)(java.lang.Object) parameters).iterator();
			for (Integer parameterItemIndex = 0; parameterss.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("parameters" + parameterItemIndex, parameterss.next());
				path.append("parameters={parameters" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (parameters != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("parameters", parameters);
			path.append("parameters={parameters}&");
		}
		// Executes the operation and returns the response.
this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);
				
	}
	
	/**
	 * Test service.

 @param object Test object.
 
	 * @throws BusinessException Any expected errors.
	 */
	
	public void constraintViolationExceptionService(
org.coldis.library.test.service.exception.ExceptionTestClass object
			) throws BusinessException {
		// Operation parameters.
		StringBuilder path = new StringBuilder(this.valueResolver
				.resolveStringValue("http://localhost:${local.server.port}/exception" + (StringUtils.isBlank("constraint-violation") ? "" : "/constraint-violation") + "?"));
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
this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);
				
	}
	

}