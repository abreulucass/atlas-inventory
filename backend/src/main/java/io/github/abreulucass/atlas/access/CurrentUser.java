package io.github.abreulucass.atlas.access;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The authenticated user of the current request. The tenant stays in {@link TenantContext}. */
public final class CurrentUser {

	private CurrentUser() {
	}

	public static UUID id() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken jwt)) {
			throw new IllegalStateException("No authenticated user in this request");
		}
		return UUID.fromString(jwt.getToken().getSubject());
	}

}
