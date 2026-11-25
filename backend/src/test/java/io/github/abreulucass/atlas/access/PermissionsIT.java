package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.AuthFixture.Session;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PermissionsIT {

	@Autowired
	Permissions permissions;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate app;

	@Value("${spring.datasource.url}")
	String url;

	@Value("${spring.flyway.user}")
	String ownerUser;

	@Value("${spring.flyway.password}")
	String ownerPassword;

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	void setRole(Session session, String role) {
		new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword)).update(
				"update memberships set role = ? where user_id = ? and organization_id = ?", role, session.userId(),
				session.organizationId());
	}

	void requireManagerAs(UUID user, UUID organization) {
		Jwt jwt = Jwt.withTokenValue("t")
			.header("alg", "HS256")
			.subject(user.toString())
			.claim("org", organization.toString())
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(60))
			.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
		TenantContext.runAs(organization, permissions::requireManager);
	}

	@Test
	void ownerAndAdminPass() throws Exception {
		Session session = new AuthFixture(mvc, app).register();

		assertThatCode(() -> requireManagerAs(session.userId(), session.organizationId())).doesNotThrowAnyException();
		setRole(session, "ADMIN");
		assertThatCode(() -> requireManagerAs(session.userId(), session.organizationId())).doesNotThrowAnyException();
	}

	@Test
	void memberIsNotAllowed() throws Exception {
		Session session = new AuthFixture(mvc, app).register();
		setRole(session, "MEMBER");

		assertThatThrownBy(() -> requireManagerAs(session.userId(), session.organizationId()))
			.isInstanceOf(NotAllowedException.class);
	}

	@Test
	void userWithoutMembershipIsNotAllowed() throws Exception {
		Session session = new AuthFixture(mvc, app).register();

		assertThatThrownBy(() -> requireManagerAs(UUID.randomUUID(), session.organizationId()))
			.isInstanceOf(NotAllowedException.class);
	}

	@Test
	void roleChangedInDatabaseAppliesOnNextCall() throws Exception {
		Session session = new AuthFixture(mvc, app).register();
		requireManagerAs(session.userId(), session.organizationId());

		setRole(session, "MEMBER");

		assertThatThrownBy(() -> requireManagerAs(session.userId(), session.organizationId()))
			.isInstanceOf(NotAllowedException.class);
	}

}
