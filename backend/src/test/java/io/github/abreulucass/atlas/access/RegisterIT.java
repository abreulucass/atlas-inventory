package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.internal.Membership;
import io.github.abreulucass.atlas.access.internal.MembershipRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RegisterIT {

	static final String PASSWORD = "x1".repeat(8);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate app;

	@Autowired
	JwtDecoder decoder;

	@MockitoSpyBean
	MembershipRepository memberships;

	static String unique() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	ResultActions register(String name, String email, String password, String slug) throws Exception {
		String body = """
				{"name": "%s", "email": "%s", "password": "%s", "organizationName": "Acme", "organizationSlug": "%s"}"""
			.formatted(name, email, password, slug);
		return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	int count(String table, String where, Object... args) {
		return app.queryForObject("select count(*) from " + table + " where " + where, Integer.class, args);
	}

	@Test
	void createsUserOrganizationAndOwnerMembership() throws Exception {
		String id = unique();
		String body = register("Ana", id + "@example.com", PASSWORD, "acme-" + id).andExpect(status().isCreated())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn()
			.getResponse()
			.getContentAsString();

		UUID userId = app.queryForObject("select id from users where email = ?", UUID.class, id + "@example.com");
		UUID orgId = app.queryForObject("select id from organizations where slug = ?", UUID.class, "acme-" + id);
		assertThat(count("memberships", "user_id = ? and organization_id = ? and role = 'OWNER'", userId, orgId))
			.isEqualTo(1);
		String access = com.jayway.jsonpath.JsonPath.read(body, "$.accessToken");
		String refresh = com.jayway.jsonpath.JsonPath.read(body, "$.refreshToken");
		Jwt jwt = decoder.decode(access);
		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.<String>getClaim("org")).isEqualTo(orgId.toString());
		assertThat(refresh).isNotBlank();
	}

	@Test
	void rejectsDuplicateEmail() throws Exception {
		String id = unique();
		register("Ana", id + "@example.com", PASSWORD, "first-" + id).andExpect(status().isCreated());

		register("Ana", id.toUpperCase() + "@Example.com", PASSWORD, "second-" + id).andExpect(status().isConflict());

		assertThat(count("users", "email = ?", id + "@example.com")).isEqualTo(1);
		assertThat(count("organizations", "slug = ?", "second-" + id)).isZero();
		assertThat(count("memberships", "organization_id in (select id from organizations where slug = ?)",
				"second-" + id)).isZero();
	}

	@Test
	void rejectsTakenOrInvalidSlug() throws Exception {
		String id = unique();
		register("Ana", id + "@example.com", PASSWORD, "taken-" + id).andExpect(status().isCreated());

		register("Bia", id + "b@example.com", PASSWORD, "taken-" + id).andExpect(status().isConflict());
		register("Cid", id + "c@example.com", PASSWORD, "Not A Slug!").andExpect(status().isBadRequest());

		assertThat(count("users", "email in (?, ?)", id + "b@example.com", id + "c@example.com")).isZero();
		assertThat(count("organizations", "slug = ?", "taken-" + id)).isEqualTo(1);
	}

	@Test
	void rejectsPasswordOutsideBounds() throws Exception {
		String id = unique();
		register("Ana", id + "a@example.com", "x".repeat(11), "short-" + id).andExpect(status().isBadRequest());
		register("Ana", id + "b@example.com", "x".repeat(129), "long-" + id).andExpect(status().isBadRequest());
		register("Ana", id + "c@example.com", "x".repeat(12), "min-" + id).andExpect(status().isCreated());
		register("Ana", id + "d@example.com", "x".repeat(128), "max-" + id).andExpect(status().isCreated());

		assertThat(count("users", "email like ?", id + "%@example.com")).isEqualTo(2);
	}

	@Test
	void rejectsInvalidEmailOrBlankName() throws Exception {
		String id = unique();
		register("Ana", "not-an-email", PASSWORD, "email-" + id).andExpect(status().isBadRequest());
		register("   ", id + "@example.com", PASSWORD, "name-" + id).andExpect(status().isBadRequest());

		assertThat(count("organizations", "slug in (?, ?)", "email-" + id, "name-" + id)).isZero();
		assertThat(count("users", "email = ?", id + "@example.com")).isZero();
	}

	@Test
	void storesOnlyArgon2idHash() throws Exception {
		String id = unique();
		register("Ana", id + "@example.com", PASSWORD, "hash-" + id).andExpect(status().isCreated());

		String stored = app.queryForObject("select password_hash from users where email = ?", String.class,
				id + "@example.com");
		assertThat(stored).startsWith("$argon2id$").doesNotContain(PASSWORD);
	}

	@Test
	void concurrentSignupsCreateOneUser() throws Exception {
		String id = unique();
		CountDownLatch go = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Integer>> results = List.of("a", "b").stream().map(suffix -> pool.submit(() -> {
				go.await();
				return register("Ana", id + "@example.com", PASSWORD, "race-" + suffix + "-" + id).andReturn()
					.getResponse()
					.getStatus();
			})).toList();
			go.countDown();
			assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(201, 409);
		}

		assertThat(count("users", "email = ?", id + "@example.com")).isEqualTo(1);
		assertThat(count("organizations", "slug like ?", "race-_-" + id)).isEqualTo(1);
	}

	@Test
	void failedSignupPersistsNothing() {
		String id = unique();
		doThrow(new IllegalStateException("boom")).when(memberships).saveAndFlush(any(Membership.class));

		assertThatThrownBy(() -> register("Ana", id + "@example.com", PASSWORD, "fail-" + id));

		assertThat(count("users", "email = ?", id + "@example.com")).isZero();
		assertThat(count("organizations", "slug = ?", "fail-" + id)).isZero();
		assertThat(count("memberships", "organization_id in (select id from organizations where slug = ?)",
				"fail-" + id)).isZero();
	}

}
