package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.company.CompanyView;
import io.github.abreulucass.atlas.company.Companies;

@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, AuthenticatedRequestIT.CompaniesProbe.class })
class AuthenticatedRequestIT {

	static final String PASSWORD = "x1".repeat(8);

	/** Test-only endpoints that read and write companies through the tenant context of the request. */
	@TestConfiguration
	@RestController
	static class CompaniesProbe {

		@Autowired
		Companies companies;

		@PostMapping("/api/test/companies")
		void create(@RequestParam String name) {
			companies.create(name, null);
		}

		@GetMapping("/api/test/companies")
		List<String> list() {
			return companies.list(true).stream().map(CompanyView::name).toList();
		}

	}

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

	record Session(String id, String accessToken, String refreshToken, UUID userId, UUID organizationId) {

		String bearer() {
			return "Bearer " + accessToken;
		}

	}

	Session register(String name) throws Exception {
		String id = UUID.randomUUID().toString().substring(0, 8);
		String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "%s", "email": "%s@example.com", "password": "%s", "organizationName": "Org %s",
				 "organizationSlug": "org-%s"}""".formatted(name, id, PASSWORD, id, id)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Session(id, JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"),
				app.queryForObject("select id from users where email = ?", UUID.class, id + "@example.com"),
				app.queryForObject("select id from organizations where slug = ?", UUID.class, "org-" + id));
	}

	ResultActions getWith(String path, String authorization) throws Exception {
		return mvc.perform(authorization == null ? get(path) : get(path).header("Authorization", authorization));
	}

	String token(String secret, Instant issuedAt, Instant expiresAt, UUID userId, UUID organizationId) {
		JwtEncoder encoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(new SecretKeySpec(secret.getBytes(), "HmacSHA256")));
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(userId.toString())
			.claim("org", organizationId.toString())
			.issuedAt(issuedAt)
			.expiresAt(expiresAt)
			.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	@Test
	void meReturnsUserAndActiveOrganization() throws Exception {
		Session ana = register("Ana");

		getWith("/api/me", ana.bearer()).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(ana.userId().toString()))
			.andExpect(jsonPath("$.name").value("Ana"))
			.andExpect(jsonPath("$.email").value(ana.id() + "@example.com"))
			.andExpect(jsonPath("$.organizationId").value(ana.organizationId().toString()))
			.andExpect(jsonPath("$.role").value("OWNER"));
	}

	@Test
	void requestSeesOnlyActiveTenantRows() throws Exception {
		Session ana = register("Ana");
		Session bob = register("Bob");
		mvc.perform(post("/api/test/companies").param("name", "Ana Co").header("Authorization", ana.bearer()))
			.andExpect(status().isOk());
		mvc.perform(post("/api/test/companies").param("name", "Bob Co").header("Authorization", bob.bearer()))
			.andExpect(status().isOk());

		assertThat(namesFor(ana)).containsExactly("Ana Co");
		assertThat(namesFor(bob)).containsExactly("Bob Co");
	}

	List<String> namesFor(Session s) throws Exception {
		String body = getWith("/api/test/companies", s.bearer()).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$");
	}

	@Test
	void rejectsMissingMalformedForgedOrExpiredToken() throws Exception {
		Session ana = register("Ana");
		Instant now = Instant.now();
		String forged = token("another-secret-another-secret-another", now, now.plusSeconds(900), ana.userId(),
				ana.organizationId());
		String expired = token("test-only-secret-test-only-secret-test-only-secret", now.minusSeconds(1200),
				now.minusSeconds(600), ana.userId(), ana.organizationId());

		getWith("/api/me", null).andExpect(status().isUnauthorized());
		getWith("/api/me", "Bearer not-a-jwt").andExpect(status().isUnauthorized());
		getWith("/api/me", "Bearer " + forged).andExpect(status().isUnauthorized());
		getWith("/api/me", "Bearer " + expired).andExpect(status().isUnauthorized());
	}

	@Test
	void ignoresOrganizationSentByClient() throws Exception {
		Session ana = register("Ana");
		Session bob = register("Bob");
		mvc.perform(post("/api/test/companies").param("name", "Bob Co").header("Authorization", bob.bearer()))
			.andExpect(status().isOk());
		String other = bob.organizationId().toString();

		getWith("/api/me", ana.bearer()).andExpect(status().isOk())
			.andExpect(jsonPath("$.organizationId").value(ana.organizationId().toString()));
		mvc.perform(get("/api/me").header("Authorization", ana.bearer())
			.header("X-Organization-Id", other)
			.param("organizationId", other))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.organizationId").value(ana.organizationId().toString()));
		mvc.perform(get("/api/test/companies").header("Authorization", ana.bearer())
			.header("X-Organization-Id", other)
			.param("organizationId", other)
			.param("organization_id", other))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
		mvc.perform(post("/api/test/companies").param("name", "Ana Co")
			.param("organizationId", other)
			.header("Authorization", ana.bearer()))
			.andExpect(status().isOk());
		mvc.perform(post("/api/test/companies").param("name", "Ana Co 2")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"organizationId\": \"%s\", \"organization_id\": \"%s\", \"tenantId\": \"%s\"}"
				.formatted(other, other, other))
			.header("Authorization", ana.bearer()))
			.andExpect(status().isOk());
		String refreshed = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"%s\", \"organizationId\": \"%s\"}".formatted(ana.refreshToken(),
					other)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		getWith("/api/me", "Bearer " + JsonPath.read(refreshed, "$.accessToken")).andExpect(status().isOk())
			.andExpect(jsonPath("$.organizationId").value(ana.organizationId().toString()));
		assertThat(namesFor(ana)).containsExactlyInAnyOrder("Ana Co", "Ana Co 2");
		assertThat(namesFor(bob)).containsExactly("Bob Co");
		getWith("/api/me", ana.bearer()).andExpect(status().isOk())
			.andExpect(jsonPath("$.organizationId").value(ana.organizationId().toString()));
	}

	@Test
	void publicEndpointsNeedNoToken() throws Exception {
		// empty or unknown payloads: each endpoint answers its own status, never 401 for a missing token
		java.util.Map<String, Integer> expected = java.util.Map.of("/api/auth/register", 400, "/api/auth/login", 400,
				"/api/auth/refresh", 400, "/api/auth/logout", 400);
		for (var entry : expected.entrySet()) {
			mvc.perform(post(entry.getKey()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().is(entry.getValue()));
		}
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"nobody@example.com\", \"password\": \"%s\"}".formatted(PASSWORD)))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"unknown\"}")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"unknown\"}")).andExpect(status().isNoContent());
		getWith("/actuator/health", null).andExpect(status().isOk());
	}

	@Test
	void removedMembershipTokenReturns401() throws Exception {
		Session ana = register("Ana");
		getWith("/api/companies", ana.bearer()).andExpect(status().isOk());

		new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword)).update(
				"delete from memberships where user_id = ? and organization_id = ?", ana.userId(),
				ana.organizationId());

		getWith("/api/companies", ana.bearer()).andExpect(status().isUnauthorized());
		getWith("/api/me", ana.bearer()).andExpect(status().isUnauthorized());
	}

}
