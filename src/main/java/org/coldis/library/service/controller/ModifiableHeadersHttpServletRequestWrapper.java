package org.coldis.library.service.controller;

import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Modifiable headers HTTP Servlet request wrapper.
 *
 */
public class ModifiableHeadersHttpServletRequestWrapper extends HttpServletRequestWrapper {

	/**
	 * Constructor.
	 *
	 * @param request Request.
	 */
	public ModifiableHeadersHttpServletRequestWrapper(final HttpServletRequest request) {
		super(request);
	}

	/**
	 * Headers.
	 */
	private MultiValuedMap<String, String> headers;

	/**
	 * Gets the headers.
	 *
	 * @return The headers.
	 */
	private MultiValuedMap<String, String> getHeaders() {
		// If the map has not been initialized.
		if (this.headers == null) {
			// Initializes the headers.
			this.headers = new ArrayListValuedHashMap<>();
			// For each header.
			final Enumeration<String> headerNames = super.getHeaderNames();
			while (headerNames.hasMoreElements()) {
				final String headerName = headerNames.nextElement();
				// For each header with the name.
				final Enumeration<String> headersValues = super.getHeaders(headerName);
				while (headersValues.hasMoreElements()) {
					final String headerValue = headersValues.nextElement();
					// Adds the header to the map.
					this.headers.put(headerName, headerValue);
				}
			}
		}
		// Returns the headers.
		return this.headers;
	}

	/**
	 * Gets the header entries.
	 *
	 * @param  name Name.
	 * @return      Header entries.
	 */
	protected Collection<Entry<String, String>> getHeaderEntries(
			final String name) {
		return this.getHeaders().entries().stream().filter(header -> name.equalsIgnoreCase(header.getKey())).collect(Collectors.toList());
	}

	/**
	 * @see jakarta.servlet.http.HttpServletRequestWrapper#getHeader(java.lang.String)
	 */
	@Override
	public String getHeader(
			final String name) {
		final Collection<Entry<String, String>> headerEntries = this.getHeaderEntries(name);
		return CollectionUtils.isEmpty(headerEntries) ? null : headerEntries.iterator().next().getValue();
	}

	/**
	 * @see jakarta.servlet.http.HttpServletRequestWrapper#getHeaderNames()
	 */
	@Override
	public Enumeration<String> getHeaderNames() {
		return Collections.enumeration(this.getHeaders().asMap().keySet());
	}

	/**
	 * @see jakarta.servlet.http.HttpServletRequestWrapper#getHeaders(java.lang.String)
	 */
	@Override
	public Enumeration<String> getHeaders(
			final String name) {
		return Collections.enumeration(this.getHeaderEntries(name).stream().map(header -> header.getValue()).collect(Collectors.toList()));
	}

	/**
	 * Adds new headers.
	 *
	 * @param name  Name.
	 * @param value Value.
	 */
	public void addHeader(
			final String name,
			final String value) {
		this.getHeaders().put(name, value);
	}

	/**
	 * Remove headers.
	 *
	 * @param name Name.
	 */
	public void removeHeader(
			final String name) {
		this.getHeaderEntries(name).stream().map(header -> header.getKey()).forEach(this.getHeaders()::remove);
	}

	/**
	 * Adds new headers.
	 *
	 * @param name  Name.
	 * @param value Value.
	 */
	public void setHeader(
			final String name,
			final String value) {
		this.removeHeader(name);
		this.addHeader(name, value);
	}

}
