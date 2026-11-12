package org.coldis.library.test.service.properties;

import org.coldis.library.service.properties.PropertiesLogHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the properties service logs for a value it sets: addresses as their scheme, hosts and
 * ports, other strings by their length only, booleans and numbers as they are.
 */
@DisplayName("Values the properties service logs")
public class PropertiesLogHelperUnitTest {

	/**
	 * Booleans, numbers and null are logged as they are.
	 */
	@Test
	@DisplayName("Booleans, numbers and null are logged as they are")
	public void testBooleanNumberAndNullLoggedAsTheyAre() {
		Assertions.assertEquals("false", PropertiesLogHelper.getLoggedValue("useTopologyForLoadBalancing", Boolean.FALSE));
		Assertions.assertEquals("200", PropertiesLogHelper.getLoggedValue("maximumPoolSize", 200));
		Assertions.assertEquals("null", PropertiesLogHelper.getLoggedValue("dataSource.url", null));
	}

	/**
	 * A database URL keeps its scheme, host and port; path and parameters, which may carry
	 * credentials, are only counted.
	 */
	@Test
	@DisplayName("A database URL keeps its host and port only")
	public void testDatabaseUrlKeepsHostAndPortOnly() {
		final String hidden = "/app?sslmode=require&password=FAKE_TEST_PASSWORD";
		final String loggedValue = PropertiesLogHelper.getLoggedValue("dataSource.pool.dataSource.jdbcUrl", "jdbc:postgresql://10.0.1.4:5432" + hidden);
		Assertions.assertEquals("jdbc:postgresql://10.0.1.4:5432 (+" + hidden.length() + " characters hidden)", loggedValue);
		Assertions.assertFalse(loggedValue.contains("FAKE"));
	}

	/**
	 * A URL with user information is logged by its length only, whatever its password holds.
	 */
	@Test
	@DisplayName("A URL with user information is logged by its length only")
	public void testUrlWithUserInformationLoggedByLength() {
		for (final String password : new String[] { "FAKE_TEST_PASSWORD", "FAKE/pass", "FAKE?pass", "FAKE#pass", "FAKE;pass", "FAKE@pass" }) {
			final String url = "jdbc:postgresql://FAKE_USER:" + password + "@10.0.1.4:5432/app";
			final String loggedValue = PropertiesLogHelper.getLoggedValue("jdbcUrl", url);
			Assertions.assertEquals("<" + url.length() + " characters>", loggedValue);
		}
	}

	/**
	 * A token in a URL path is not logged.
	 */
	@Test
	@DisplayName("A token in a URL path is not logged")
	public void testPathTokenHidden() {
		final String hidden = "/services/T000/B000/FAKEtoken123";
		final String loggedValue = PropertiesLogHelper.getLoggedValue("alertWebhookUrl", "https://hooks.example.com" + hidden);
		Assertions.assertEquals("https://hooks.example.com (+" + hidden.length() + " characters hidden)", loggedValue);
	}

	/**
	 * Several hosts, and a URL with no path, keep their hosts and ports.
	 */
	@Test
	@DisplayName("Several hosts, and a URL with no path, keep their hosts and ports")
	public void testMultiHostAndPlainUrlsKept() {
		Assertions.assertEquals("jdbc:postgresql://10.0.1.4:5432,10.0.1.5:5432 (+4 characters hidden)",
				PropertiesLogHelper.getLoggedValue("jdbcUrl", "jdbc:postgresql://10.0.1.4:5432,10.0.1.5:5432/app"));
		Assertions.assertEquals("http://10.0.1.6:8080", PropertiesLogHelper.getLoggedValue("serviceEndpoint", "http://10.0.1.6:8080"));
	}

	/**
	 * Parameters after a semicolon (SQL Server style) are hidden too.
	 */
	@Test
	@DisplayName("Parameters after a semicolon are hidden")
	public void testSemicolonParametersHidden() {
		final String hidden = ";user=FAKE_USER;password=FAKE_TEST_PASSWORD";
		final String loggedValue = PropertiesLogHelper.getLoggedValue("url", "jdbc:sqlserver://10.0.1.7:1433" + hidden);
		Assertions.assertEquals("jdbc:sqlserver://10.0.1.7:1433 (+" + hidden.length() + " characters hidden)", loggedValue);
	}

	/**
	 * A host or a port is logged as it is; one with user information only by its length.
	 */
	@Test
	@DisplayName("A host or a port is logged as it is, one with user information by its length")
	public void testHostAndPortLogged() {
		Assertions.assertEquals("10.0.0.5", PropertiesLogHelper.getLoggedValue("connectionFactory.serverLocator.initialConnectors.0.params.host", "10.0.0.5"));
		Assertions.assertEquals("61616", PropertiesLogHelper.getLoggedValue("connectionFactory.serverLocator.initialConnectors.0.params.port", "61616"));
		Assertions.assertEquals("<10 characters>", PropertiesLogHelper.getLoggedValue("host", "FAKE@host1"));
	}

	/**
	 * Any other string is logged by its length only: it may be a password or a key.
	 */
	@Test
	@DisplayName("Any other string is logged by its length only")
	public void testOtherStringLoggedByLength() {
		Assertions.assertEquals("<18 characters>", PropertiesLogHelper.getLoggedValue("dataSource.password", "FAKE_TEST_PASSWORD"));
		Assertions.assertEquals("<9 characters>", PropertiesLogHelper.getLoggedValue("someSetting", "Abc123xyz"));
		Assertions.assertEquals("<15 characters>", PropertiesLogHelper.getLoggedValue("supportApiKey", "FAKE-test-key-1"));
		Assertions.assertEquals("<10 characters>", PropertiesLogHelper.getLoggedValue("reportPortPassword", "FAKE-1234x"));
	}


	/**
	 * An address value that is not only hosts and ports, with or without a scheme, is not logged in
	 * full: it can carry credentials in its parameters or path.
	 */
	@Test
	@DisplayName("An address value that is not only hosts and ports is logged by its length")
	public void testAddressValueWithParametersLoggedByLength() {
		for (final String value : new String[] { "10.0.1.4:5432/app?password=FAKE_TEST_PASSWORD", "jdbc:postgresql:appdb?user=FAKE_USER&password=FAKE_TEST_PASSWORD",
				"jdbc:h2:file:/data/app;USER=FAKE_USER;PASSWORD=FAKE_TEST_PASSWORD", "myhost/api/hooks/FAKEtoken123" }) {
			Assertions.assertEquals("<" + value.length() + " characters>", PropertiesLogHelper.getLoggedValue("jdbcUrl", value));
		}
		Assertions.assertEquals("10.0.1.4:5432,10.0.1.5:5432", PropertiesLogHelper.getLoggedValue("brokerHosts", "10.0.1.4:5432,10.0.1.5:5432"));
		Assertions.assertEquals("[::1]:61616", PropertiesLogHelper.getLoggedValue("brokerAddress", "[::1]:61616"));
	}

	/**
	 * Only a whole word or a camel-case suffix makes a name an address: transport, report and ghost
	 * do not.
	 */
	@Test
	@DisplayName("Only a whole word or a camel-case suffix makes a name an address")
	public void testAddressNameNeedsAWordBoundary() {
		Assertions.assertEquals("10.0.0.5", PropertiesLogHelper.getLoggedValue("jdbcURL", "10.0.0.5"));
		Assertions.assertEquals("10.0.0.5", PropertiesLogHelper.getLoggedValue("serviceEndpoint", "10.0.0.5"));
		for (final String name : new String[] { "transport", "report", "ghost", "passport" }) {
			Assertions.assertEquals("<8 characters>", PropertiesLogHelper.getLoggedValue(name, "10.0.0.5"));
		}
	}


	/**
	 * Only a scheme at the start makes a value a URL; a "://" further in, or a URL whose host part is
	 * not plain hosts and ports, is logged by its length only.
	 */
	@Test
	@DisplayName("A misplaced scheme or an unusual host part is logged by its length")
	public void testMisplacedSchemeAndUnusualHostLoggedByLength() {
		for (final String value : new String[] { "hooks.example.com/FAKEtoken123?next=https://x", "jdbc:h2:file:/data/app;PASSWORD=FAKE_TEST_PASSWORD;AUTO_SERVER_URL=tcp://10.0.0.1",
				"file:///etc/app/FAKE_secret.key", "http://my_host/hooks/FAKEtoken123" }) {
			final String loggedValue = PropertiesLogHelper.getLoggedValue("callbackUrl", value);
			Assertions.assertEquals("<" + value.length() + " characters>", loggedValue);
			Assertions.assertFalse(loggedValue.contains("FAKE"));
		}
	}

}
