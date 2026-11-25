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

	/**
	 * Marks a live token as used; the database decides the winner when two requests race for it. Runs in the
	 * caller's transaction, under the family lock, and refuses when the family was already revoked.
	 */
	Optional<Rotated> rotate(String token) {
		byte[] hash = TokenIssuer.sha256(token);
		if (!lockFamilyOf(hash)) {
			return Optional.empty();
		}
		return jdbc.sql("""
				update refresh_tokens t set used_at = now()
				where t.token_hash = ? and t.used_at is null and t.revoked_at is null and t.expires_at > now()
				  and not exists (select 1 from refresh_tokens r where r.family_id = t.family_id and r.revoked_at is not null)
				returning t.user_id, t.organization_id, t.family_id""")
			.param(hash)
			.query((rs, i) -> new Rotated(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
					rs.getObject(3, UUID.class)))
			.optional();
	}

	/** Reuse of a rotated token kills its whole chain; own transaction, committed before the 401. */
	@Transactional
	void revokeFamilyIfReused(String token) {
		byte[] hash = TokenIssuer.sha256(token);
		if (!lockFamilyOf(hash)) {
			return;
		}
		jdbc.sql("""
				update refresh_tokens set revoked_at = now()
				where revoked_at is null and family_id in
				      (select family_id from refresh_tokens where token_hash = ? and used_at is not null)""")
			.param(hash)
			.update();
	}

	// Serializes rotation, descendant issue and reuse revocation of one family (transaction-scoped lock).
	private boolean lockFamilyOf(byte[] hash) {
		Optional<UUID> family = jdbc.sql("select family_id from refresh_tokens where token_hash = ?")
			.param(hash)
			.query(UUID.class)
			.optional();
		family.ifPresent(f -> jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?, 0))")
			.param(f.toString())
			.query((rs, i) -> 1)
			.single());
		return family.isPresent();
	}

	@Transactional
	void revoke(String token) {
		jdbc.sql("update refresh_tokens set revoked_at = now() where token_hash = ? and revoked_at is null")
			.param(TokenIssuer.sha256(token))
			.update();
	}

}
