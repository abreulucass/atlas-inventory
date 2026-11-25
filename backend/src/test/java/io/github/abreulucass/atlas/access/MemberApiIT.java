package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

	ResultActions addAs(Session as, String json) throws Exception {
		return mvc.perform(post("/api/members").header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	ResultActions addAs(Session as, String email, String role) throws Exception {
		return addAs(as, "{\"email\": \"%s\", \"role\": \"%s\"}".formatted(email, role));
	}

	/** Switches the user's tokens to the organization. */
	Session switchTo(Session user, UUID organizationId) throws Exception {
		String body = mvc.perform(post("/api/auth/switch-organization").header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"organizationId\": \"%s\"}".formatted(organizationId)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Session(user.name(), user.email(), "Bearer " + JsonPath.read(body, "$.accessToken"),
				JsonPath.read(body, "$.refreshToken"), user.userId(), organizationId);
	}

	/** A newly registered user that the manager adds with the role, holding tokens for the manager's organization. */
	Session join(Session manager, String name, String role) throws Exception {
		Session user = register(name);
		addAs(manager, user.email(), role).andExpect(status().isCreated());
		return switchTo(user, manager.organizationId());
	}

	int membershipCount(UUID userId, UUID organizationId) {
		return app.queryForObject("select count(*) from memberships where user_id = ? and organization_id = ?",
				Integer.class, userId, organizationId);
	}

	int memberCount(UUID organizationId) {
		return app.queryForObject("select count(*) from memberships where organization_id = ?", Integer.class,
				organizationId);
	}

	String roleOf(UUID userId, UUID organizationId) {
		return app.queryForObject("select role from memberships where user_id = ? and organization_id = ?",
				String.class, userId, organizationId);
	}

	void archive(UUID organizationId) {
		owner().update("update organizations set archived_at = now() where id = ?", organizationId);
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

	@Test
	void ownerAddsWithAnyRole() throws Exception {
		Session owner = register("Olga");
		for (String role : List.of("OWNER", "ADMIN", "MEMBER")) {
			Session user = register("User " + role);

			addAs(owner, user.email(), role).andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/members/" + user.userId()))
				.andExpect(jsonPath("$.userId").value(user.userId().toString()))
				.andExpect(jsonPath("$.name").value(user.name()))
				.andExpect(jsonPath("$.email").value(user.email()))
				.andExpect(jsonPath("$.role").value(role));

			assertThat(roleOf(user.userId(), owner.organizationId())).isEqualTo(role);
		}
		assertThat(memberCount(owner.organizationId())).isEqualTo(4);
	}

	@Test
	void adminAddsMember() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "Adam", "ADMIN");
		Session user = register("Mia");

		addAs(admin, user.email(), "MEMBER").andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/members/" + user.userId()))
			.andExpect(jsonPath("$.userId").value(user.userId().toString()))
			.andExpect(jsonPath("$.name").value("Mia"))
			.andExpect(jsonPath("$.email").value(user.email()))
			.andExpect(jsonPath("$.role").value("MEMBER"));

		assertThat(roleOf(user.userId(), owner.organizationId())).isEqualTo("MEMBER");
	}

	@Test
	void adminCannotAddAboveMember() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "Adam", "ADMIN");
		Session user = register("Mia");

		addAs(admin, user.email(), "OWNER").andExpect(status().isForbidden());
		addAs(admin, user.email(), "ADMIN").andExpect(status().isForbidden());

		assertThat(membershipCount(user.userId(), owner.organizationId())).isZero();
		assertThat(memberCount(owner.organizationId())).isEqualTo(2);
	}

	@Test
	void memberCannotAdd() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");
		Session user = register("Mia");

		for (String role : List.of("OWNER", "ADMIN", "MEMBER")) {
			addAs(member, user.email(), role).andExpect(status().isForbidden());
		}

		assertThat(membershipCount(user.userId(), owner.organizationId())).isZero();
		assertThat(memberCount(owner.organizationId())).isEqualTo(2);
	}

	@Test
	void addNormalizesEmail() throws Exception {
		Session owner = register("Olga");
		Session user = register("Ana");

		addAs(owner, "  " + user.email().toUpperCase(java.util.Locale.ROOT) + "  ", "MEMBER")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.userId").value(user.userId().toString()))
			.andExpect(jsonPath("$.email").value(user.email()));

		assertThat(roleOf(user.userId(), owner.organizationId())).isEqualTo("MEMBER");
	}

	@Test
	void addUnknownEmailReturns404() throws Exception {
		Session owner = register("Olga");

		addAs(owner, "nobody-" + UUID.randomUUID() + "@example.com", "MEMBER").andExpect(status().isNotFound());

		assertThat(memberCount(owner.organizationId())).isEqualTo(1);
	}

	@Test
	void addExistingMemberReturns409() throws Exception {
		Session owner = register("Olga");
		Session user = register("Ana");
		addAs(owner, user.email(), "MEMBER").andExpect(status().isCreated());

		addAs(owner, user.email(), "ADMIN").andExpect(status().isConflict());
		addAs(owner, owner.email(), "MEMBER").andExpect(status().isConflict());

		assertThat(membershipCount(user.userId(), owner.organizationId())).isEqualTo(1);
		assertThat(roleOf(user.userId(), owner.organizationId())).isEqualTo("MEMBER");
		assertThat(roleOf(owner.userId(), owner.organizationId())).isEqualTo("OWNER");
		assertThat(memberCount(owner.organizationId())).isEqualTo(2);
	}

	@Test
	void addInvalidInputReturns400() throws Exception {
		Session owner = register("Olga");
		Session user = register("Ana");
		String email = user.email();

		for (String json : List.of("{\"email\": \"\", \"role\": \"MEMBER\"}",
				"{\"email\": \"   \", \"role\": \"MEMBER\"}", "{\"email\": \"not-an-email\", \"role\": \"MEMBER\"}",
				"{\"role\": \"MEMBER\"}", "{\"email\": \"%s\"}".formatted(email),
				"{\"email\": \"%s\", \"role\": \"SUPER\"}".formatted(email),
				"{\"email\": \"%s\", \"role\": \"member\"}".formatted(email),
				"{\"email\": \"%s\", \"role\": \"\"}".formatted(email))) {
			addAs(owner, json).andExpect(status().isBadRequest());
		}

		assertThat(membershipCount(user.userId(), owner.organizationId())).isZero();
		assertThat(memberCount(owner.organizationId())).isEqualTo(1);
	}

	@Test
	void addInArchivedOrganizationReturns409() throws Exception {
		Session owner = register("Olga");
		Session user = register("Ana");
		archive(owner.organizationId());

		addAs(owner, user.email(), "MEMBER").andExpect(status().isConflict());

		assertThat(membershipCount(user.userId(), owner.organizationId())).isZero();
	}

	@Test
	void addedUserCanSwitchToOrganization() throws Exception {
		Session owner = register("Olga");
		Session user = register("Ana");
		addAs(owner, user.email(), "ADMIN").andExpect(status().isCreated());

		String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(user.email(), PASSWORD)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		Session loggedIn = new Session(user.name(), user.email(), "Bearer " + JsonPath.read(login, "$.accessToken"),
				JsonPath.read(login, "$.refreshToken"), user.userId(), user.organizationId());
		Session switched = switchTo(loggedIn, owner.organizationId());

		mvc.perform(get("/api/me").header("Authorization", switched.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.organizationId").value(owner.organizationId().toString()))
			.andExpect(jsonPath("$.role").value("ADMIN"));
		List<String> ids = JsonPath.read(
				listAs(switched).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$[*].userId");
		assertThat(ids).containsExactlyInAnyOrder(owner.userId().toString(), user.userId().toString());
	}

	@Test
	void concurrentAddsYieldOne201() throws Exception {
		int threads = 5;
		var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
		try {
			for (int round = 0; round < 5; round++) {
				Session owner = register("Olga");
				Session user = register("Ana");
				var barrier = new java.util.concurrent.CyclicBarrier(threads);
				var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
				for (int i = 0; i < threads; i++) {
					futures.add(pool.submit(() -> {
						barrier.await();
						return addAs(owner, user.email(), "MEMBER").andReturn().getResponse().getStatus();
					}));
				}
				var statuses = new java.util.ArrayList<Integer>();
				for (var f : futures) {
					statuses.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
				}
				assertThat(statuses).containsOnly(201, 409);
				assertThat(statuses.stream().filter(c -> c == 201).count()).isEqualTo(1);
				assertThat(membershipCount(user.userId(), owner.organizationId())).isEqualTo(1);
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

}
