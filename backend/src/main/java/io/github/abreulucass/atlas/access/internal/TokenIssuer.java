package io.github.abreulucass.atlas.access.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Issues the access JWT (15 min) and the opaque refresh token (30 days, stored only as SHA-256). */
@Component
class TokenIssuer {

	static final Duration ACCESS_TTL = Duration.ofMinutes(15);

	static final Duration REFRESH_TTL = Duration.ofDays(30);

	private final SecureRandom random = new SecureRandom();

	private final JwtEncoder encoder;

	private final JdbcClient jdbc;

	private final MembershipRepository memberships;

	TokenIssuer(JwtEncoder encoder, JdbcClient jdbc, MembershipRepository memberships) {
		this.encoder = encoder;
		this.jdbc = jdbc;
		this.memberships = memberships;
	}

	/** Runs in the caller's transaction and holds the membership row until it commits, so a removal waits for it. */
	@Transactional
	TokenPair issue(UUID userId, UUID organizationId, UUID familyId) {
		memberships.lockForShare(userId, organizationId).orElseThrow(NotAMemberException::new);
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(userId.toString())
			.claim("org", organizationId.toString())
			.issuedAt(now)
			.expiresAt(now.plus(ACCESS_TTL))
			.build();
		String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();

		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		jdbc.sql("""
				insert into refresh_tokens (id, token_hash, family_id, user_id, organization_id, expires_at)
				values (?, ?, ?, ?, ?, ?)""")
			.params(UUID.randomUUID(), sha256(refresh), familyId, userId, organizationId,
					OffsetDateTime.ofInstant(now.plus(REFRESH_TTL), ZoneOffset.UTC))
			.update();
		return new TokenPair(access, refresh, "Bearer", ACCESS_TTL.toSeconds());
	}

	static byte[] sha256(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
