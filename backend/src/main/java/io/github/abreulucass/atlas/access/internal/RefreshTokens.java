package io.github.abreulucass.atlas.access.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Lifecycle of stored refresh tokens: live -> used (rotated) or revoked, never back. */
@Component
class RefreshTokens {

	record Rotated(UUID userId, UUID organizationId, UUID familyId) {
	}

	private final JdbcClient jdbc;

	RefreshTokens(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Marks a live token as used; the database decides the winner when two requests race for it. */
	Optional<Rotated> rotate(String token) {
		return jdbc.sql("""
				update refresh_tokens set used_at = now()
				where token_hash = ? and used_at is null and revoked_at is null and expires_at > now()
				returning user_id, organization_id, family_id""")
			.param(TokenIssuer.sha256(token))
			.query((rs, i) -> new Rotated(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
					rs.getObject(3, UUID.class)))
			.optional();
	}

	/** Reuse of a rotated token kills its whole chain; own transaction, committed before the 401. */
	@Transactional
	void revokeFamilyIfReused(String token) {
		jdbc.sql("""
				update refresh_tokens set revoked_at = now()
				where revoked_at is null and family_id in
				      (select family_id from refresh_tokens where token_hash = ? and used_at is not null)""")
			.param(TokenIssuer.sha256(token))
			.update();
	}

	@Transactional
	void revoke(String token) {
		jdbc.sql("update refresh_tokens set revoked_at = now() where token_hash = ? and revoked_at is null")
			.param(TokenIssuer.sha256(token))
			.update();
	}

}
