package org.coldis.library.service.properties;

import java.util.regex.Pattern;

/**
 * How a property value set at runtime may appear in the log. Values can be credentials, or carry
 * them (a database URL with a password, a webhook with a token in its path), and the log is kept.
 */
public final class PropertiesLogHelper {

	/**
	 * Last segment of a property name whose value is an address: the whole segment (host, ports, url)
	 * or a camel-case suffix (jdbcUrl, brokerHosts, serviceEndpoint, jdbcURL). A word that only ends
	 * with the same letters (transport, report, ghost, supportApiKey) is not an address.
	 */
	private static final Pattern ADDRESS_PROPERTY_NAME = Pattern
			.compile("(host|port|url|uri|address|endpoint)(s|es)?|.*[a-z0-9](Host|Port|Url|URL|Uri|URI|Address|Endpoint)(s|es)?");

	/**
	 * A value that is only hosts and ports: host, IP address (IPv6 in brackets), host:port, or a
	 * comma-separated list of them. Anything else (a path, parameters) is not logged in full.
	 */
	private static final Pattern HOSTS_AND_PORTS = Pattern.compile("[A-Za-z0-9.\\-\\[\\]:]+(,[A-Za-z0-9.\\-\\[\\]:]+)*");

	/**
	 * A URL: a well-formed scheme, compound ones included (jdbc:postgresql), right at the start and
	 * followed by "://". A "://" further in (a callback parameter, an H2 AUTO_SERVER_URL) does not
	 * make the text before it a scheme.
	 */
	private static final Pattern URL = Pattern.compile("[A-Za-z][A-Za-z0-9+.\\-]*(:[A-Za-z][A-Za-z0-9+.\\-]*)*://.*", Pattern.DOTALL);

	/**
	 * Characters that end the hosts and ports of a URL: a path, or parameters (? # and the ; of some
	 * JDBC drivers).
	 */
	private static final Pattern URL_ADDRESS_END = Pattern.compile("[/?#;]");

	/**
	 * Not instantiated.
	 */
	private PropertiesLogHelper() {
	}

	/**
	 * Gets the length placeholder logged instead of a value.
	 *
	 * @param  value Value.
	 * @return       The length placeholder.
	 */
	private static String getLengthPlaceholder(
			final String value) {
		return "<" + value.length() + " characters>";
	}

	/**
	 * Whether a property holds an address, judged by the last segment of its name (after the last
	 * dot). The name is a path variable: a query such as ?fieldAccess=true never reaches it.
	 *
	 * @param  name Property name.
	 * @return      Whether the property holds an address.
	 */
	private static boolean isAddressProperty(
			final String name) {
		boolean isAddress = false;
		if (name != null) {
			final String lastSegment = name.substring(name.lastIndexOf('.') + 1);
			isAddress = PropertiesLogHelper.ADDRESS_PROPERTY_NAME.matcher(lastSegment).matches();
		}
		return isAddress;
	}

	/**
	 * Gets the address part of a URL: its scheme, hosts and ports. The rest (path and parameters) is
	 * only counted, since a path can carry a token as well (webhook and bot addresses, signed links).
	 * When what follows the scheme does not start with hosts and ports, the URL is logged by its
	 * length only.
	 *
	 * @param  url URL (starts with a scheme and "://", and has no user information).
	 * @return     The address part of the URL.
	 */
	private static String getUrlAddress(
			final String url) {
		String loggedValue = null;
		final int schemeEnd = url.indexOf("://") + 3;
		final String afterScheme = url.substring(schemeEnd);
		final String hostsAndPorts = PropertiesLogHelper.URL_ADDRESS_END.split(afterScheme, 2)[0];
		if (PropertiesLogHelper.HOSTS_AND_PORTS.matcher(hostsAndPorts).matches()) {
			final int hiddenLength = afterScheme.length() - hostsAndPorts.length();
			loggedValue = url.substring(0, schemeEnd) + hostsAndPorts + (hiddenLength > 0 ? " (+" + hiddenLength + " characters hidden)" : "");
		}
		else {
			loggedValue = PropertiesLogHelper.getLengthPlaceholder(url);
		}
		return loggedValue;
	}

	/**
	 * Gets the value as it may be logged: booleans and numbers as they are; for an address property
	 * (named after a host, port, URL, URI, address or endpoint), a URL as its scheme, hosts and ports
	 * and a value that is only hosts and ports as it is; anything else, and any value with an @, by
	 * its length only.
	 *
	 * @param  name  Property name.
	 * @param  value Property value.
	 * @return       The value as it may be logged.
	 */
	public static String getLoggedValue(
			final String name,
			final Object value) {
		String loggedValue = null;
		if ((value == null) || (value instanceof Boolean) || (value instanceof Number)) {
			loggedValue = String.valueOf(value);
		}
		else {
			final String textValue = value.toString();
			// An @ means user information, or an unusual value: by its length only, wherever it is,
			// so a password with an unencoded / ? # or ; cannot be cut in two.
			final boolean isLoggableAddress = PropertiesLogHelper.isAddressProperty(name) && !textValue.contains("@");
			if (isLoggableAddress && PropertiesLogHelper.URL.matcher(textValue).matches()) {
				loggedValue = PropertiesLogHelper.getUrlAddress(textValue);
			}
			else if (isLoggableAddress && PropertiesLogHelper.HOSTS_AND_PORTS.matcher(textValue).matches()) {
				loggedValue = textValue;
			}
			else {
				loggedValue = PropertiesLogHelper.getLengthPlaceholder(textValue);
			}
		}
		return loggedValue;
	}

}
