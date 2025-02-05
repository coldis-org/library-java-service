package org.coldis.library.test.service.exception;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.ExtendedValidator;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.client.generator.ServiceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exception handler service.
 */
@RestController
@RequestMapping(path = "exception")
@ServiceClient(
		namespace = "org.coldis.library.test.service.exception",
		targetPath = "src/test/java",
		endpoint = "http://localhost:${local.server.port}/exception"
)
public class ExceptionHandlerService {

	/**
	 * Extended validator.
	 */
	@Autowired
	private ExtendedValidator extendedValidator;

	/**
	 * Test service.
	 *
	 * @param  code              Message code.
	 * @param  parameters        Parameters.
	 * @throws BusinessException Exception.
	 */
	@RequestMapping(
			path = "business",
			method = RequestMethod.POST
	)
	public void businessExceptionService(
			@RequestParam(defaultValue = "error")
			final String code,
			@RequestParam(required = false)
			final Object[] parameters) throws BusinessException {
		throw new BusinessException(new SimpleMessage(code, parameters));
	}

	/**
	 * Test service.
	 *
	 * @param  code                 Message code.
	 * @param  parameters           Parameters.
	 * @throws IntegrationException Exception.
	 */
	@RequestMapping(
			path = "integration",
			method = RequestMethod.POST
	)
	public void integrationExceptionService(
			@RequestParam(defaultValue = "error")
			final String code,
			@RequestParam(required = false)
			final Object[] parameters) throws IntegrationException {
		throw new IntegrationException(new SimpleMessage(code, parameters));
	}

	/**
	 * Test service.
	 *
	 * @param object Test object.
	 */
	@RequestMapping(
			path = "constraint-violation",
			method = RequestMethod.POST
	)
	public void constraintViolationExceptionService(
			@RequestBody
			final ExceptionTestClass object) {
		this.extendedValidator.validateAndThrowViolations(object);
	}

}
