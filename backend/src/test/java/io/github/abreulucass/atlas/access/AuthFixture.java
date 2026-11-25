package io.github.abreulucass.atlas.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** Signs users up and calls the auth endpoints for the session ITs. */
record AuthFixture(MockMvc mvc, JdbcTemplate app) {

	static final String PASSWORD = "x1".repeat(8);

	record Session(String id, String accessToken, String refreshToken, UUID userId, UUID organizationId) {

		String bearer() {
			return "Bearer " + accessToken;
		}

	}

	Session register() throws Exception {
		String id = UUID.randomUUID().toString().substring(0, 8);
		String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Ana", "email": "%s@example.com", "password": "%s", "organizationName": "Org %s",
				 "organizationSlug": "org-%s"}""".formatted(id, PASSWORD, id, id)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Session(id, JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"),
				app.queryForObject("select id from users where email = ?", UUID.class, id + "@example.com"),
				app.queryForObject("select id from organizations where slug = ?", UUID.class, "org-" + id));
	}

	ResultActions postJson(String path, String json) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	ResultActions refresh(String refreshToken) throws Exception {
		return postJson("/api/auth/refresh", "{\"refreshToken\": \"%s\"}".formatted(refreshToken));
	}

	ResultActions logout(String refreshToken) throws Exception {
		return postJson("/api/auth/logout", "{\"refreshToken\": \"%s\"}".formatted(refreshToken));
	}

}
