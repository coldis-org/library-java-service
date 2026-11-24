package org.coldis.library.test.service.properties;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet of its own, outside Spring MVC, that answers 200 on any path under
 * {@link #PATH}. Tomcat routes to it by the path it resolves, so a path that
 * starts with the properties endpoint and leaves it through ".." reaches it
 * when nothing refuses it.
 */
@Configuration
public class PropertiesConnectorTestServletConfiguration {

	/**
	 * Path the servlet answers under.
	 */
	public static final String PATH = "/properties-connector-test-servlet";

	/**
	 * Registers the servlet.
	 *
	 * @return The registration.
	 */
	@Bean
	public ServletRegistrationBean<HttpServlet> propertiesConnectorTestServlet() {
		return new ServletRegistrationBean<>(new HttpServlet() {

			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
					final HttpServletRequest request,
					final HttpServletResponse response) {
				response.setStatus(HttpServletResponse.SC_OK);
			}

		}, PropertiesConnectorTestServletConfiguration.PATH + "/*");
	}

}
