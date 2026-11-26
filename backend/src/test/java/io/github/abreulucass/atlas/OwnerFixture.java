package io.github.abreulucass.atlas;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.github.abreulucass.atlas.access.TenantContext;

/** Runs in-process work as the tenant and as an authenticated OWNER of it, since reads fail closed without a user. */
public final class OwnerFixture {

	private static final ConcurrentMap<UUID, UUID> OWNERS = new ConcurrentHashMap<>();

	private OwnerFixture() {
	}

	public static <T> T callAsOwner(JdbcTemplate app, UUID tenant, Callable<T> work) throws Exception {
		UUID user = OWNERS.computeIfAbsent(tenant, t -> {
			UUID id = UUID.randomUUID();
			app.update("insert into users (id, name, email, password_hash, created_at) values (?, 'Owner', ?, 'x', now())",
					id, id + "@example.com");
			app.update("insert into memberships (id, user_id, organization_id, role, created_at) values (?, ?, ?, 'OWNER', now())",
					UUID.randomUUID(), id, t);
			return id;
		});
		return callAs(tenant, user, work);
	}

	public static <T> T callAs(UUID tenant, UUID user, Callable<T> work) throws Exception {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).issuedAt(Instant.now()).build();
		SecurityContext previous = SecurityContextHolder.getContext();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(context);
		try {
			return TenantContext.callAs(tenant, work);
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}

}
