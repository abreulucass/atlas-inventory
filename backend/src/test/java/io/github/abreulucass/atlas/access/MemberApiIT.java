package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.github.abreulucass.atlas.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberApiIT {

	static final String PASSWORD = "x1".repeat(8);

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

	/** A user and the tokens they hold for one organization. */
	record Session(String name, String email, String bearer, String refreshToken, UUID userId, UUID organizationId) {
	}

	JdbcTemplate owner() {
		return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
	}

	/** Registers a user who becomes OWNER of a new organization. */
	Session register(String name) throws Exception {
		String id = UUID.randomUUID().toString().substring(0, 8);
		String email = id + "@example.com";
		String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "%s", "email": "%s", "password": "%s", "organizationName": "Org %s",
				 "organizationSlug": "org-%s"}""".formatted(name, email, PASSWORD, id, id)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Session(name, email, "Bearer " + JsonPath.read(body, "$.accessToken"),
				JsonPath.read(body, "$.refreshToken"),
				app.queryForObject("select id from users where email = ?", UUID.class, email),
				app.queryForObject("select id from organizations where slug = ?", UUID.class, "org-" + id));
	}

	/** Inserts a membership directly, as the database owner. */
	void seedMembership(Session user, UUID organizationId, String role) {
		owner().update(
				"insert into memberships (id, user_id, organization_id, role, created_at) values (?, ?, ?, ?, now())",
				UUID.randomUUID(), user.userId(), organizationId, role);
	}

	ResultActions listAs(Session as) throws Exception {
		return mvc.perform(get("/api/members").header("Authorization", as.bearer()));
	}

	@Test
	void listReturnsMembersOrderedByNameThenEmail() throws Exception {
		Session carla = register("Carla");
		Session ana1 = register("Ana");
		Session ana2 = register("Ana");
		Session bruno = register("Bruno");
		seedMembership(ana1, carla.organizationId(), "ADMIN");
		seedMembership(ana2, carla.organizationId(), "MEMBER");
		seedMembership(bruno, carla.organizationId(), "MEMBER");

		List<Session> anas = List.of(ana1, ana2)
			.stream()
			.sorted(Comparator.comparing(Session::email))
			.toList();
		List<Session> expected = List.of(anas.get(0), anas.get(1), bruno, carla);
		Map<UUID, String> roles = Map.of(carla.userId(), "OWNER", ana1.userId(), "ADMIN", ana2.userId(), "MEMBER",
				bruno.userId(), "MEMBER");

		ResultActions result = listAs(carla).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
		for (int i = 0; i < expected.size(); i++) {
			Session s = expected.get(i);
			result.andExpect(jsonPath("$[%d].userId".formatted(i)).value(s.userId().toString()))
				.andExpect(jsonPath("$[%d].name".formatted(i)).value(s.name()))
				.andExpect(jsonPath("$[%d].email".formatted(i)).value(s.email()))
				.andExpect(jsonPath("$[%d].role".formatted(i)).value(roles.get(s.userId())));
		}
	}

	@Test
	void listNeverShowsOtherOrganization() throws Exception {
		Session ana = register("Ana");
		Session bob = register("Bob");
		Session caio = register("Caio");
		seedMembership(caio, bob.organizationId(), "MEMBER");

		List<String> anaList = JsonPath.read(
				listAs(ana).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$[*].userId");
		List<String> bobList = JsonPath.read(
				listAs(bob).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$[*].userId");

		assertThat(anaList).containsExactly(ana.userId().toString());
		assertThat(bobList).containsExactlyInAnyOrder(bob.userId().toString(), caio.userId().toString());
	}

	@Test
	void unauthenticatedReturns401() throws Exception {
		Session ana = register("Ana");
		String target = "/api/members/" + ana.userId();

		mvc.perform(get("/api/members")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"role\": \"MEMBER\"}".formatted(ana.email())))
			.andExpect(status().isUnauthorized());
		mvc.perform(patch(target).contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"ADMIN\"}"))
			.andExpect(status().isUnauthorized());
		mvc.perform(delete(target)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/members").header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized());
		assertThat(app.queryForObject("select count(*) from memberships where user_id = ?", Integer.class,
				ana.userId())).isEqualTo(1);
		assertThat(app.queryForObject("select role from memberships where user_id = ?", String.class, ana.userId()))
			.isEqualTo("OWNER");
	}

}
