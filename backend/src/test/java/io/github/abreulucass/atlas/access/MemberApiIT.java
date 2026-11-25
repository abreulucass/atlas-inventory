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

	ResultActions changeAs(Session as, Object userId, String json) throws Exception {
		return mvc.perform(patch("/api/members/" + userId).header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	ResultActions changeAs(Session as, Session target, String role) throws Exception {
		return changeAs(as, target.userId(), "{\"role\": \"%s\"}".formatted(role));
	}

	ResultActions removeAs(Session as, Object userId) throws Exception {
		return mvc.perform(delete("/api/members/" + userId).header("Authorization", as.bearer()));
	}

	int liveRefreshTokens(UUID userId, UUID organizationId) {
		return app.queryForObject(
				"select count(*) from refresh_tokens where user_id = ? and organization_id = ? and revoked_at is null",
				Integer.class, userId, organizationId);
	}

	int ownerCount(UUID organizationId) {
		return app.queryForObject("select count(*) from memberships where organization_id = ? and role = 'OWNER'",
				Integer.class, organizationId);
	}

	/** Runs each call at once behind a start barrier and returns the HTTP statuses. */
	List<Integer> race(List<java.util.concurrent.Callable<ResultActions>> calls) throws Exception {
		var pool = java.util.concurrent.Executors.newFixedThreadPool(calls.size());
		try {
			var barrier = new java.util.concurrent.CyclicBarrier(calls.size());
			var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
			for (var call : calls) {
				futures.add(pool.submit(() -> {
					barrier.await();
					return call.call().andReturn().getResponse().getStatus();
				}));
			}
			var statuses = new java.util.ArrayList<Integer>();
			for (var f : futures) {
				statuses.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"%s\"}".formatted(refreshToken)));
	}

	/** A transaction of the database owner, left open so the test decides when its locks are released. */
	java.sql.Connection ownerTransaction() throws java.sql.SQLException {
		java.sql.Connection connection = java.sql.DriverManager.getConnection(url, ownerUser, ownerPassword);
		connection.setAutoCommit(false);
		return connection;
	}

	long query(java.sql.Connection connection, String sql, Object... params) throws java.sql.SQLException {
		try (var statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				statement.setObject(i + 1, params[i]);
			}
			try (var rs = statement.executeQuery()) {
				rs.next();
				return rs.getObject(1) instanceof Number n ? n.longValue() : 0;
			}
		}
	}

	/** Waits until exactly this many backends wait for a lock. */
	void awaitLockWaiters(int count) throws InterruptedException {
		awaitTrue(() -> app.queryForObject("select count(distinct pid) from pg_locks where not granted",
				Integer.class) == count, count + " lock waiters");
	}

	/** Waits until a backend waits for the transaction with this id to end. */
	void awaitWaitingOn(long transactionId) throws InterruptedException {
		awaitTrue(() -> app.queryForObject(
				"select count(*) from pg_locks where not granted and locktype = 'transactionid' and transactionid::text = ?",
				Integer.class, Long.toString(transactionId)) == 1, "a waiter on transaction " + transactionId);
	}

	void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out waiting for " + what);
			}
			Thread.sleep(20);
		}
	}

	int membershipCount(UUID userId, UUID organizationId) {
		return app.queryForObject("select count(*) from memberships where user_id = ? and organization_id = ?",
				Integer.class, userId, organizationId);
	}

	Map<String, Object> membershipRow(UUID userId, UUID organizationId) {
		return app.queryForMap("""
				select id, user_id, organization_id, role, created_at from memberships
				where user_id = ? and organization_id = ?""", userId, organizationId);
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
		Map<String, Object> userRow = membershipRow(user.userId(), owner.organizationId());
		Map<String, Object> ownerRow = membershipRow(owner.userId(), owner.organizationId());

		addAs(owner, user.email(), "ADMIN").andExpect(status().isConflict());
		addAs(owner, owner.email(), "MEMBER").andExpect(status().isConflict());

		assertThat(membershipRow(user.userId(), owner.organizationId())).isEqualTo(userRow);
		assertThat(membershipRow(owner.userId(), owner.organizationId())).isEqualTo(ownerRow);

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
	void ownerChangesRole() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");

		changeAs(owner, member, "ADMIN").andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(member.userId().toString()))
			.andExpect(jsonPath("$.name").value("Mel"))
			.andExpect(jsonPath("$.email").value(member.email()))
			.andExpect(jsonPath("$.role").value("ADMIN"));

		assertThat(roleOf(member.userId(), owner.organizationId())).isEqualTo("ADMIN");
		List<String> listed = JsonPath.read(
				listAs(owner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$[?(@.userId == '%s')].role".formatted(member.userId()));
		assertThat(listed).containsExactly("ADMIN");

		changeAs(owner, member, "OWNER").andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
		assertThat(roleOf(member.userId(), owner.organizationId())).isEqualTo("OWNER");
	}

	@Test
	void sameRoleReturns200() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "Adam", "ADMIN");
		Map<String, Object> adminRow = membershipRow(admin.userId(), owner.organizationId());
		Map<String, Object> ownerRow = membershipRow(owner.userId(), owner.organizationId());

		changeAs(owner, admin, "ADMIN").andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(admin.userId().toString()))
			.andExpect(jsonPath("$.name").value("Adam"))
			.andExpect(jsonPath("$.email").value(admin.email()))
			.andExpect(jsonPath("$.role").value("ADMIN"));
		changeAs(owner, owner, "OWNER").andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));

		assertThat(membershipRow(admin.userId(), owner.organizationId())).isEqualTo(adminRow);
		assertThat(membershipRow(owner.userId(), owner.organizationId())).isEqualTo(ownerRow);

		assertThat(roleOf(admin.userId(), owner.organizationId())).isEqualTo("ADMIN");
		assertThat(roleOf(owner.userId(), owner.organizationId())).isEqualTo("OWNER");
		assertThat(memberCount(owner.organizationId())).isEqualTo(2);
	}

	@Test
	void adminCannotChangeAboveMember() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "Adam", "ADMIN");
		Session otherAdmin = join(owner, "Alba", "ADMIN");
		Session member = join(owner, "Mel", "MEMBER");

		for (String role : List.of("OWNER", "ADMIN", "MEMBER")) {
			changeAs(admin, owner, role).andExpect(status().isForbidden());
			changeAs(admin, otherAdmin, role).andExpect(status().isForbidden());
		}
		changeAs(admin, member, "OWNER").andExpect(status().isForbidden());
		changeAs(admin, member, "ADMIN").andExpect(status().isForbidden());

		assertThat(roleOf(owner.userId(), owner.organizationId())).isEqualTo("OWNER");
		assertThat(roleOf(otherAdmin.userId(), owner.organizationId())).isEqualTo("ADMIN");
		assertThat(roleOf(member.userId(), owner.organizationId())).isEqualTo("MEMBER");
		assertThat(roleOf(admin.userId(), owner.organizationId())).isEqualTo("ADMIN");
	}

	@Test
	void memberCannotChangeRole() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");
		Session other = join(owner, "Max", "MEMBER");

		for (String role : List.of("OWNER", "ADMIN", "MEMBER")) {
			changeAs(member, other, role).andExpect(status().isForbidden());
			changeAs(member, member, role).andExpect(status().isForbidden());
			changeAs(member, owner, role).andExpect(status().isForbidden());
		}

		assertThat(roleOf(member.userId(), owner.organizationId())).isEqualTo("MEMBER");
		assertThat(roleOf(other.userId(), owner.organizationId())).isEqualTo("MEMBER");
		assertThat(roleOf(owner.userId(), owner.organizationId())).isEqualTo("OWNER");
	}

	@Test
	void lastOwnerCannotBeDemoted() throws Exception {
		Session owner = register("Olga");
		join(owner, "Adam", "ADMIN");

		changeAs(owner, owner, "ADMIN").andExpect(status().isConflict());
		changeAs(owner, owner, "MEMBER").andExpect(status().isConflict());

		assertThat(roleOf(owner.userId(), owner.organizationId())).isEqualTo("OWNER");
		assertThat(ownerCount(owner.organizationId())).isEqualTo(1);
	}

	@Test
	void changeUnknownOrOtherTenantReturns404() throws Exception {
		Session owner = register("Olga");
		Session stranger = register("Sara");

		changeAs(owner, stranger, "MEMBER").andExpect(status().isNotFound());
		changeAs(owner, UUID.randomUUID(), "{\"role\": \"MEMBER\"}").andExpect(status().isNotFound());
		changeAs(owner, "not-a-uuid", "{\"role\": \"MEMBER\"}").andExpect(status().isNotFound());

		assertThat(roleOf(stranger.userId(), stranger.organizationId())).isEqualTo("OWNER");
		assertThat(membershipCount(stranger.userId(), owner.organizationId())).isZero();
	}

	@Test
	void changeInvalidRoleReturns400() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");

		for (String json : List.of("{}", "{\"role\": null}", "{\"role\": \"\"}", "{\"role\": \"SUPER\"}",
				"{\"role\": \"admin\"}")) {
			changeAs(owner, member.userId(), json).andExpect(status().isBadRequest());
		}

		assertThat(roleOf(member.userId(), owner.organizationId())).isEqualTo("MEMBER");
	}

	@Test
	void changeInArchivedOrganizationReturns409() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");
		archive(owner.organizationId());

		changeAs(owner, member, "ADMIN").andExpect(status().isConflict());

		assertThat(roleOf(member.userId(), owner.organizationId())).isEqualTo("MEMBER");
	}

	@Test
	void demotedAdminLosesWriteImmediately() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "Adam", "ADMIN");
		String company = "{\"name\": \"%s\"}";

		mvc.perform(post("/api/companies").header("Authorization", admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(company.formatted("Before"))).andExpect(status().isCreated());

		changeAs(owner, admin, "MEMBER").andExpect(status().isOk());

		mvc.perform(post("/api/companies").header("Authorization", admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(company.formatted("After"))).andExpect(status().isForbidden());
		List<String> names = JsonPath.read(mvc.perform(get("/api/companies").header("Authorization", owner.bearer()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$[*].name");
		assertThat(names).containsExactly("Before");
	}

	@Test
	void concurrentOwnerDemotionsLeaveOneOwner() throws Exception {
		for (String demotedTo : List.of("ADMIN", "MEMBER", "ADMIN", "MEMBER", "ADMIN")) {
			Session first = register("Olga");
			Session second = join(first, "Otto", "OWNER");

			List<Integer> statuses = race(List.of(() -> changeAs(first, second, demotedTo),
					() -> changeAs(second, first, demotedTo)));

			assertThat(statuses.stream().filter(c -> c == 200).count()).isEqualTo(1);
			assertThat(statuses).containsAnyOf(403, 409).allMatch(c -> c == 200 || c == 403 || c == 409);
			assertThat(ownerCount(first.organizationId())).isEqualTo(1);
		}
	}

	@Test
	void ownerRemovesMember() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "Adam", "ADMIN");
		Session member = join(owner, "Mel", "MEMBER");
		Session coOwner = join(owner, "Otto", "OWNER");

		for (Session target : List.of(admin, member, coOwner)) {
			removeAs(owner, target.userId()).andExpect(status().isNoContent());
			assertThat(membershipCount(target.userId(), owner.organizationId())).isZero();
		}

		List<String> ids = JsonPath.read(
				listAs(owner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$[*].userId");
		assertThat(ids).containsExactly(owner.userId().toString());
	}

	@Test
	void adminRemovesMember() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "Adam", "ADMIN");
		Session member = join(owner, "Mel", "MEMBER");

		removeAs(admin, member.userId()).andExpect(status().isNoContent());

		assertThat(membershipCount(member.userId(), owner.organizationId())).isZero();
		assertThat(memberCount(owner.organizationId())).isEqualTo(2);
	}

	@Test
	void adminCannotRemoveAboveMember() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "Adam", "ADMIN");
		Session otherAdmin = join(owner, "Alba", "ADMIN");

		removeAs(admin, owner.userId()).andExpect(status().isForbidden());
		removeAs(admin, otherAdmin.userId()).andExpect(status().isForbidden());

		assertThat(roleOf(owner.userId(), owner.organizationId())).isEqualTo("OWNER");
		assertThat(roleOf(otherAdmin.userId(), owner.organizationId())).isEqualTo("ADMIN");
		assertThat(memberCount(owner.organizationId())).isEqualTo(3);
	}

	@Test
	void memberCannotRemove() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");
		Session other = join(owner, "Max", "MEMBER");

		removeAs(member, other.userId()).andExpect(status().isForbidden());
		removeAs(member, owner.userId()).andExpect(status().isForbidden());

		assertThat(roleOf(other.userId(), owner.organizationId())).isEqualTo("MEMBER");
		assertThat(roleOf(owner.userId(), owner.organizationId())).isEqualTo("OWNER");
		assertThat(memberCount(owner.organizationId())).isEqualTo(3);
	}

	@Test
	void selfRemovalReturns403() throws Exception {
		Session owner = register("Olga");
		join(owner, "Otto", "OWNER");
		Session admin = join(owner, "Adam", "ADMIN");

		removeAs(owner, owner.userId()).andExpect(status().isForbidden());
		removeAs(admin, admin.userId()).andExpect(status().isForbidden());

		assertThat(roleOf(owner.userId(), owner.organizationId())).isEqualTo("OWNER");
		assertThat(roleOf(admin.userId(), owner.organizationId())).isEqualTo("ADMIN");
		assertThat(memberCount(owner.organizationId())).isEqualTo(3);
	}

	@Test
	void removeUnknownOrOtherTenantReturns404() throws Exception {
		Session owner = register("Olga");
		Session stranger = register("Sara");

		removeAs(owner, stranger.userId()).andExpect(status().isNotFound());
		removeAs(owner, UUID.randomUUID()).andExpect(status().isNotFound());
		removeAs(owner, "not-a-uuid").andExpect(status().isNotFound());

		assertThat(roleOf(stranger.userId(), stranger.organizationId())).isEqualTo("OWNER");
		assertThat(memberCount(owner.organizationId())).isEqualTo(1);
	}

	@Test
	void removeInArchivedOrganizationReturns409() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");
		archive(owner.organizationId());

		removeAs(owner, member.userId()).andExpect(status().isConflict());

		assertThat(roleOf(member.userId(), owner.organizationId())).isEqualTo("MEMBER");
	}

	@Test
	void removedMemberRefreshReturns401() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");
		assertThat(liveRefreshTokens(member.userId(), owner.organizationId())).isEqualTo(1);

		removeAs(owner, member.userId()).andExpect(status().isNoContent());

		assertThat(liveRefreshTokens(member.userId(), owner.organizationId())).isZero();
		mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"%s\"}".formatted(member.refreshToken())))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void removedMemberAccessTokenReturns401() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");
		mvc.perform(get("/api/companies").header("Authorization", member.bearer())).andExpect(status().isOk());

		removeAs(owner, member.userId()).andExpect(status().isNoContent());

		mvc.perform(get("/api/companies").header("Authorization", member.bearer()))
			.andExpect(status().isUnauthorized());
		listAs(member).andExpect(status().isUnauthorized());
	}

	@Test
	void removedUserKeepsAccountAndOtherOrganizations() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "ADMIN");
		UUID ownOrganization = app.queryForObject(
				"select organization_id from memberships where user_id = ? and organization_id <> ?", UUID.class,
				member.userId(), owner.organizationId());

		removeAs(owner, member.userId()).andExpect(status().isNoContent());

		assertThat(roleOf(member.userId(), ownOrganization)).isEqualTo("OWNER");
		String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(member.email(), PASSWORD)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + JsonPath.read(login, "$.accessToken")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.organizationId").value(ownOrganization.toString()))
			.andExpect(jsonPath("$.role").value("OWNER"));
	}

	@Test
	void concurrentOwnerRemovalsLeaveOneOwner() throws Exception {
		for (int round = 0; round < 5; round++) {
			Session first = register("Olga");
			Session second = join(first, "Otto", "OWNER");

			List<Integer> statuses = race(
					List.of(() -> removeAs(first, second.userId()), () -> removeAs(second, first.userId())));

			assertThat(statuses.stream().filter(c -> c == 204).count()).isEqualTo(1);
			assertThat(statuses).containsAnyOf(403, 409).allMatch(c -> c == 204 || c == 403 || c == 409);
			assertThat(ownerCount(first.organizationId())).isEqualTo(1);
			assertThat(memberCount(first.organizationId())).isEqualTo(1);
		}
	}

	@Test
	void refreshRacingRemovalLeavesNoLiveToken() throws Exception {
		for (int round = 0; round < 5; round++) {
			Session owner = register("Olga");
			Session member = join(owner, "Mel", "MEMBER");
			List<java.util.concurrent.Callable<ResultActions>> calls = new java.util.ArrayList<>();
			for (int i = 0; i < 20; i++) {
				String refreshToken = switchTo(member, owner.organizationId()).refreshToken();
				calls.add(() -> mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
					.content("{\"refreshToken\": \"%s\"}".formatted(refreshToken))));
			}
			calls.add(() -> removeAs(owner, member.userId()));

			List<Integer> statuses = race(calls);

			assertThat(statuses.getLast()).isEqualTo(204);
			assertThat(statuses.subList(0, statuses.size() - 1)).allMatch(c -> c == 200 || c == 401);
			assertThat(membershipCount(member.userId(), owner.organizationId())).isZero();
			assertThat(liveRefreshTokens(member.userId(), owner.organizationId())).isZero();
		}
	}

	@Test
	void malformedIdFollowsPrecedence() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");

		changeAs(member, "bad-id", "{\"role\": \"MEMBER\"}").andExpect(status().isForbidden());
		removeAs(member, "bad-id").andExpect(status().isForbidden());

		archive(owner.organizationId());
		removeAs(owner, "bad-id").andExpect(status().isConflict());
		changeAs(owner, "bad-id", "{\"role\": \"MEMBER\"}").andExpect(status().isConflict());

		assertThat(memberCount(owner.organizationId())).isEqualTo(2);
	}

	@Test
	void descendantRefreshDuringRemovalNeverDeadlocks() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "Mel", "MEMBER");
		String other = switchTo(member, owner.organizationId()).refreshToken();
		String byHash = " from refresh_tokens where token_hash = sha256(convert_to(?, 'UTF8'))";
		UUID family = app.queryForObject("select family_id" + byHash, UUID.class, member.refreshToken());
		var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
		try (var familyLock = ownerTransaction(); var tokenLock = ownerTransaction()) {
			// holds the first refresh after its membership lock, and the removal inside its revoke
			query(familyLock, "select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l", family.toString());
			query(tokenLock, "select 1" + byHash + " for update", other);
			long tokenLockXid = query(tokenLock, "select txid_current()");

			var first = pool.submit(() -> refresh(member.refreshToken()).andReturn().getResponse());
			awaitLockWaiters(1);
			var removal = pool.submit(() -> removeAs(owner, member.userId()).andReturn().getResponse());
			awaitLockWaiters(2);
			familyLock.commit();

			var firstResponse = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
			assertThat(firstResponse.getStatus()).isEqualTo(200);
			String descendant = JsonPath.read(firstResponse.getContentAsString(), "$.refreshToken");
			awaitWaitingOn(tokenLockXid);
			// the descendant is consumed while the removal has deleted the membership and not committed
			var second = pool.submit(() -> refresh(descendant).andReturn().getResponse());
			awaitLockWaiters(2);
			tokenLock.commit();

			assertThat(removal.get(30, java.util.concurrent.TimeUnit.SECONDS).getStatus()).isEqualTo(204);
			assertThat(second.get(30, java.util.concurrent.TimeUnit.SECONDS).getStatus()).isIn(200, 401);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(membershipCount(member.userId(), owner.organizationId())).isZero();
		assertThat(liveRefreshTokens(member.userId(), owner.organizationId())).isZero();
	}

	@Test
	void everyRoleListsMembers() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "Adam", "ADMIN");
		Session member = join(owner, "Mel", "MEMBER");
		register("Sara");
		Map<String, String> expected = Map.of(owner.userId().toString(), "OWNER", admin.userId().toString(), "ADMIN",
				member.userId().toString(), "MEMBER");

		for (Session as : List.of(owner, admin, member)) {
			String body = listAs(as).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andReturn()
				.getResponse()
				.getContentAsString();
			List<Map<String, String>> listed = JsonPath.read(body, "$[*]");
			assertThat(listed).allSatisfy(m -> assertThat(m.get("role")).isEqualTo(expected.get(m.get("userId"))));
			assertThat(listed.stream().map(m -> m.get("userId")).toList())
				.containsExactlyInAnyOrderElementsOf(expected.keySet());
		}
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
