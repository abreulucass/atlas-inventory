package io.github.abreulucass.atlas.access.internal;

import java.io.IOException;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.abreulucass.atlas.access.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs the rest of the request in the tenant of the token's {@code org} claim (AUTH-04). Not a bean: it is added to
 * the security chain only. The tenant is scoped to this thread, so controllers must not hand work to other threads.
 */
class TenantContextFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) {
			chain.doFilter(request, response);
			return;
		}
		UUID organizationId = UUID.fromString(jwt.getToken().getClaimAsString("org"));
		try {
			TenantContext.callAs(organizationId, () -> {
				chain.doFilter(request, response);
				return null;
			});
		}
		catch (IOException | ServletException | RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
