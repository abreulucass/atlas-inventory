package io.github.abreulucass.atlas.access.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Refuses a token whose {@code (sub, org)} no longer has a membership, so a removal takes effect at once. */
@Component
class MembershipValidator implements OAuth2TokenValidator<Jwt> {

	private static final OAuth2Error NOT_A_MEMBER = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
			"The token's membership no longer exists", null);

	private final JdbcClient jdbc;

	MembershipValidator(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		UUID userId;
		UUID organizationId;
		try {
			userId = UUID.fromString(jwt.getSubject());
			organizationId = UUID.fromString(jwt.getClaimAsString("org"));
		}
		catch (IllegalArgumentException | NullPointerException e) {
			return OAuth2TokenValidatorResult.failure(NOT_A_MEMBER);
		}
		// memberships has no RLS, so this needs no tenant and runs outside any transaction
		boolean member = jdbc
			.sql("select exists(select 1 from memberships where user_id = ? and organization_id = ?)")
			.params(userId, organizationId)
			.query(Boolean.class)
			.single();
		return member ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(NOT_A_MEMBER);
	}

}
