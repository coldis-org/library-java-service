package org.coldis.library.test.service.jms;

import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.client.generator.ServiceClient;
import org.coldis.library.service.helper.MultiLayerSessionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Enhanced message converter test service.
 */
@RestController
@RequestMapping("enhanced-message-converter-service")
@ServiceClient(
		namespace = "org.coldis.library.test.service.jms",
		targetPath = "src/test/java",
		endpoint = "http://localhost:9090/enhanced-message-converter-service"
)
public class EnhancedMessageConverterTestService {

	/** Multi-layer session helper. */
	@Autowired
	private MultiLayerSessionHelper multiLayerSessionHelper;

	/** Object mapper. */
	@Autowired
	private ObjectMapper objectMapper;

	/** JMS template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** Sends a message with header, parameters and query parameters. */
	@RequestMapping(
			path = "send-message",
			method = RequestMethod.POST
	)
	public void sendMessage(
			@RequestParam
			final String queue,
			@RequestBody
			final DtoTestObject data,
			@RequestHeader(required = false)
			final Long testJmsAttr1,
			@RequestParam(required = false)
			final Long testJmsAttr2,
			@RequestParam(required = false)
			final Long sessionAttr3,
			@RequestParam(required = false)
			final Long sessionAttr4,
			@RequestParam(required = false)
			final Long sessionAttr5,
			@RequestParam(required = false)
			// @CookieValue(required = false) FIXME
			final Long testJmsAttr6,
			@RequestParam(required = false)
			final Long sessionAttrN) {
		this.multiLayerSessionHelper.getServletRequest().setAttribute("testJmsAttr3", sessionAttr3);
		this.multiLayerSessionHelper.getServletRequest().getSession(true).setAttribute("testJmsAttr4", sessionAttr4);
		this.multiLayerSessionHelper.getThreadSession().put("testJmsAttr5", sessionAttr5);
		this.multiLayerSessionHelper.getThreadSession().put("testJmsAttrN", sessionAttrN);
		this.jmsTemplate.convertAndSend(queue, ObjectMapperHelper.convert(this.objectMapper, data, DtoTestObjectDto.class, true));
	}

}
