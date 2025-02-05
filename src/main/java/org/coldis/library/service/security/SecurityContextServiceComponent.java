package org.coldis.library.service.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Security context service. */
@Component
public class SecurityContextServiceComponent {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(SecurityContextServiceComponent.class);

	/**
	 * Gets the current user authorities.
	 *
	 * @return Gets the current user authorities.
	 */
	public Authentication getCurrentUser() {
		return SecurityContextHolder.getContext().getAuthentication();
	}

	/**
	 * Gets the current user authorities.
	 *
	 * @return Gets the current user authorities.
	 */
	public String getCurrentUserName() {
		final Authentication user = this.getCurrentUser();
		return (user == null ? null : user.getName());
	}

	/**
	 * Gets the current user authorities.
	 *
	 * @return Gets the current user authorities.
	 */
	public Boolean getAuthenticated() {
		final Authentication user = this.getCurrentUser();
		return (user != null) && user.isAuthenticated();
	}

	/**
	 * Gets the current user authorities.
	 *
	 * @return Gets the current user authorities.
	 */
	public List<String> getCurrentUserAuthorities() {
		final Authentication user = this.getCurrentUser();
		return (user == null ? null : user.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList()));
	}

	/**
	 * Checks if the current user has any of the given authorities.
	 *
	 * @param  desiredAuthorities Authorities.
	 * @return                    if the current user has any of the given
	 *                            authorities.
	 */
	public Boolean hasAnyStringAuthority(
			final Collection<String> userAuthorities,
			final String... desiredAuthorities) {
		return (CollectionUtils.isNotEmpty(userAuthorities) && userAuthorities.stream().anyMatch(currentUserAuthority -> Set.of(desiredAuthorities).stream()
				.anyMatch(currentNeedAuthority -> StringUtils.equalsIgnoreCase(currentUserAuthority, currentNeedAuthority))));
	}

	/**
	 * Checks if the current user has any of the given authorities.
	 *
	 * @param  desiredAuthorities Authorities.
	 * @return                    if the current user has any of the given
	 *                            authorities.
	 */
	public Boolean hasAnyAuthority(
			final Collection<? extends GrantedAuthority> userAuthorities,
			final String... desiredAuthorities) {
		return CollectionUtils.isNotEmpty(userAuthorities)
				&& this.hasAnyStringAuthority(userAuthorities.stream().map(GrantedAuthority::getAuthority).toList(), desiredAuthorities);
	}

	/**
	 * Checks if the current user has any of the given authorities.
	 *
	 * @param  authorities Authorities.
	 * @return             if the current user has any of the given authorities.
	 */
	public Boolean hasAnyAuthority(
			final String... authorities) {
		final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return ((authentication != null) && this.hasAnyAuthority(authentication.getAuthorities(), authorities));
	}

	/**
	 * Checks if the current user has an authority.
	 *
	 * @param  authority Authority.
	 * @return           if the current user has an authority.
	 */
	public Boolean hasAuthority(
			final String authority) {
		return this.hasAnyAuthority(authority);
	}

	/**
	 * Checks if the current user has any of the given authorities prefixes.
	 *
	 * @param  authorities Authorities prefixes.
	 * @return             if the current user has any of the given authorities
	 *                     prefixes.
	 */
	public Boolean hasAnyStringAuthorityPrefix(
			final Collection<String> userAuthorities,
			final String... desiredAuthorities) {
		return (CollectionUtils.isNotEmpty(userAuthorities) && userAuthorities.stream().anyMatch(currentUserAuthority -> Set.of(desiredAuthorities).stream()
				.anyMatch(currentNeedAuthority -> StringUtils.startsWithIgnoreCase(currentUserAuthority, currentNeedAuthority))));
	}

	/**
	 * Checks if the current user has any of the given authorities prefixes.
	 *
	 * @param  authorities Authorities prefixes.
	 * @return             if the current user has any of the given authorities
	 *                     prefixes.
	 */
	public Boolean hasAnyAuthorityPrefix(
			final Collection<? extends GrantedAuthority> userAuthorities,
			final String... desiredAuthorities) {
		return CollectionUtils.isNotEmpty(userAuthorities)
				&& this.hasAnyStringAuthorityPrefix(userAuthorities.stream().map(GrantedAuthority::getAuthority).toList(), desiredAuthorities);
	}

	/**
	 * Checks if the current user has any of the given authorities prefixes.
	 *
	 * @param  authorities Authorities prefixes.
	 * @return             if the current user has any of the given authorities
	 *                     prefixes.
	 */
	public Boolean hasAnyAuthorityPrefix(
			final String... authorities) {
		final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return ((authentication != null) && this.hasAnyAuthorityPrefix(authentication.getAuthorities(), authorities));
	}

	/**
	 * Checks if the current user has an authority prefix.
	 *
	 * @param  authority Authority prefix.
	 * @return           if the current user has an authority prefix.
	 */
	public Boolean hasAuthorityPrefix(
			final String authority) {
		return this.hasAnyAuthorityPrefix(authority);
	}

	/**
	 * Checks if the current user has any of the given authorities. Throws an
	 * exception if the user has not.
	 *
	 * @param  authorities       Authorities.
	 * @return                   if the current user has any of the given
	 *                           authorities.
	 * @throws BusinessException If the user does not have any of the given
	 *                               authorities.
	 */
	public void validateAnyAuthority(
			final String... authorities) throws BusinessException {
		if (!this.hasAnyAuthority(authorities)) {
			throw new BusinessException(new SimpleMessage("access.denied"), HttpStatus.FORBIDDEN.value());
		}
	}

	/**
	 * Checks if the current user has an authority. Throws an exception if the user
	 * has not.
	 *
	 * @param  authority         Authority.
	 * @return                   if the current user has an authority.
	 * @throws BusinessException If the user does not have the authority.
	 */
	public void validateAuthority(
			final String authority) throws BusinessException {
		this.validateAnyAuthority(authority);
	}

}
