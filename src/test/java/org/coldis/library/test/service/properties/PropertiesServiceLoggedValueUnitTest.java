package org.coldis.library.test.service.properties;

import org.coldis.library.service.properties.PropertiesService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the properties service logs for a value it sets: addresses as their scheme, hosts, ports
 * and path, other strings by their length only, booleans and numbers as they are.
 */
@DisplayName("Values the properties service logs")
public class PropertiesServiceLoggedValueUnitTest {

	/**
	 * Booleans, numbers and null are logged as they are.
	 */
	@Test
	@DisplayName("Booleans, numbers and null are logged as they are")
	public void testBooleanNumberAndNullLoggedAsTheyAre() {
		Assertions.assertEquals("false", PropertiesService.getLoggedValue("useTopologyForLoadBalancing", Boolean.FALSE));
		Assertions.assertEquals("200", PropertiesService.getLoggedValue("maximumPoolSize", 200));
		Assertions.assertEquals("null", PropertiesService.getLoggedValue("dataSource.url", null));
	}

	/**
	 * A database URL keeps its scheme, host, port and database, and loses user information and
	 * parameters, which may carry credentials.
	 */
	@Test
	@DisplayName("A database URL loses its credentials and parameters")
	public void testDatabaseUrlLosesCredentialsAndParameters() {
		final String loggedValue = PropertiesService.getLoggedValue("dataSource.pool.dataSource.jdbcUrl",
				"jdbc:postgresql://FAKE_USER:FAKE_TEST_PASSWORD@10.0.1.4:5432/app?sslmode=require&password=FAKE_TEST_PASSWORD");
		Assertions.assertEquals("jdbc:postgresql://10.0.1.4:5432/app (parameters hidden)", loggedValue);
		Assertions.assertFalse(loggedValue.contains("FAKE"));
	}

	/**
	 * Several hosts, and a URL with no parameters, are kept whole.
	 */
	@Test
	@DisplayName("Several hosts, and a URL with no parameters, are kept whole")
	public void testMultiHostAndPlainUrlsKept() {
		Assertions.assertEquals("jdbc:postgresql://10.0.1.4:5432,10.0.1.5:5432/app",
				PropertiesService.getLoggedValue("jdbcUrl", "jdbc:postgresql://10.0.1.4:5432,10.0.1.5:5432/app"));
		Assertions.assertEquals("http://10.0.1.6:8080", PropertiesService.getLoggedValue("serviceEndpoint", "http://10.0.1.6:8080"));
	}

	/**
	 * Parameters after a semicolon (SQL Server style) are hidden too.
	 */
	@Test
	@DisplayName("Parameters after a semicolon are hidden")
	public void testSemicolonParametersHidden() {
		final String loggedValue = PropertiesService.getLoggedValue("url", "jdbc:sqlserver://10.0.1.7:1433;user=FAKE_USER;password=FAKE_TEST_PASSWORD");
		Assertions.assertEquals("jdbc:sqlserver://10.0.1.7:1433 (parameters hidden)", loggedValue);
	}

	/**
	 * A host or a port is logged as it is; one with user information only by its length.
	 */
	@Test
	@DisplayName("A host or a port is logged as it is, one with user information by its length")
	public void testHostAndPortLogged() {
		Assertions.assertEquals("10.0.0.5", PropertiesService.getLoggedValue("connectionFactory.serverLocator.initialConnectors.0.params.host", "10.0.0.5"));
		Assertions.assertEquals("61616", PropertiesService.getLoggedValue("connectionFactory.serverLocator.initialConnectors.0.params.port", "61616"));
		Assertions.assertEquals("<10 characters>", PropertiesService.getLoggedValue("host", "FAKE@host1"));
	}

	/**
	 * Any other string is logged by its length only: it may be a password or a key.
	 */
	@Test
	@DisplayName("Any other string is logged by its length only")
	public void testOtherStringLoggedByLength() {
		Assertions.assertEquals("<18 characters>", PropertiesService.getLoggedValue("dataSource.password", "FAKE_TEST_PASSWORD"));
		Assertions.assertEquals("<9 characters>", PropertiesService.getLoggedValue("someSetting", "Abc123xyz"));
		Assertions.assertEquals("<15 characters>", PropertiesService.getLoggedValue("supportApiKey", "FAKE-test-key-1"));
		Assertions.assertEquals("<10 characters>", PropertiesService.getLoggedValue("reportPortPassword", "FAKE-1234x"));
	}

}
