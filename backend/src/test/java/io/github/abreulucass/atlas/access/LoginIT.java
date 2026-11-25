package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.github.abreulucass.atlas.TestcontainersConfiguration;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginIT {

	static final String PASSWORD = "x1".repeat(8);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate app;

	@Autowired
	JwtDecoder decoder;

	ResultActions send(String path, String body) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	ResultActions login(String email, String password) throws Exception {
		return send("/api/auth/login", """
				{"email": "%s", "password": "%s"}""".formatted(email, password));
	}

	@Test
	void issuesTokensForFirstMembership() throws Exception {
		String id = UUID.randomUUID().toString().substring(0, 8);
		send("/api/auth/register", """
				{"name": "Ana", "email": "%s@example.com", "password": "%s", "organizationName": "First",
				 "organizationSlug": "first-%s"}""".formatted(id, PASSWORD, id)).andExpect(status().isCreated());
		UUID userId = app.queryForObject("select id from users where email = ?", UUID.class, id + "@example.com");
		UUID firstOrg = app.queryForObject("select id from organizations where slug = ?", UUID.class, "first-" + id);
		UUID secondOrg = UUID.randomUUID();
		app.update("insert into organizations (id, name, slug) values (?, 'Second', ?)", secondOrg, "second-" + id);
		app.update("insert into memberships (id, user_id, organization_id, role, created_at) "
				+ "values (?, ?, ?, 'ADMIN', now() + interval '1 minute')", UUID.randomUUID(), userId, secondOrg);

		String body = login((id + "@EXAMPLE.com").toUpperCase(), PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andExpect(jsonPath("$.refreshToken").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		Jwt jwt = decoder.decode(JsonPath.<String>read(body, "$.accessToken"));
		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.<String>getClaim("org")).isEqualTo(firstOrg.toString());
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void unknownEmailAndWrongPasswordLookAlike() throws Exception {
		String id = UUID.randomUUID().toString().substring(0, 8);
		send("/api/auth/register", """
				{"name": "Ana", "email": "%s@example.com", "password": "%s", "organizationName": "Acme",
				 "organizationSlug": "acme-%s"}""".formatted(id, PASSWORD, id)).andExpect(status().isCreated());

		String unknown = login("nobody-" + id + "@example.com", PASSWORD).andExpect(status().isUnauthorized())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String wrong = login(id + "@example.com", "wrong".repeat(3)).andExpect(status().isUnauthorized())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(unknown).isNotBlank().isEqualTo(wrong);
	}

}
