package io.github.abreulucass.atlas.access.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import io.github.abreulucass.atlas.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TokenIssuerIT {

	@Autowired
	TokenIssuer issuer;

	@Autowired
	JwtDecoder decoder;

	@Autowired
	JdbcTemplate app;

	@Test
	void issuesJwtAndStoresOnlyRefreshTokenHash() {
		UUID user = UUID.randomUUID();
		UUID org = UUID.randomUUID();
		UUID family = UUID.randomUUID();
		app.update("insert into users (id, name, email, password_hash, created_at) values (?, 'N', ?, 'h', now())", user,
				user + "@example.com");
		app.update("insert into organizations (id, name, slug) values (?, 'O', ?)", org, "o-" + org);
		Instant before = Instant.now();

		TokenPair pair = issuer.issue(user, org, family);

		Jwt jwt = decoder.decode(pair.accessToken());
		assertThat(jwt.getSubject()).isEqualTo(user.toString());
		assertThat(jwt.<String>getClaim("org")).isEqualTo(org.toString());
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
		assertThat(jwt.getExpiresAt()).isBetween(before.plus(Duration.ofMinutes(15)).minusSeconds(1),
				Instant.now().plus(Duration.ofMinutes(15)).plusSeconds(1));
		assertThat(pair.expiresIn()).isEqualTo(900);

		Map<String, Object> row = app.queryForMap(
				"select token_hash, family_id, user_id, organization_id, expires_at from refresh_tokens where user_id = ?",
				user);
		assertThat((byte[]) row.get("token_hash")).isEqualTo(TokenIssuer.sha256(pair.refreshToken()))
			.isNotEqualTo(pair.refreshToken().getBytes());
		assertThat(row).containsEntry("family_id", family).containsEntry("organization_id", org);
		assertThat(((Timestamp) row.get("expires_at")).toInstant())
			.isCloseTo(before.plus(Duration.ofDays(30)), within(5, java.time.temporal.ChronoUnit.SECONDS));
		assertThat(app.queryForObject("select count(*) from refresh_tokens where encode(token_hash, 'escape') = ?",
				Integer.class, pair.refreshToken())).isZero();
	}

}
