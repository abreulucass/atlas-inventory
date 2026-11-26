package io.github.abreulucass.atlas.stock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import io.github.abreulucass.atlas.TestcontainersConfiguration;

/** Users, roles, grants, stock fixtures and ledger snapshots over HTTP and as the owner, for the stock ITs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class StockITSupport {

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

	int codes;

	record Session(String bearer, UUID userId, UUID organizationId) {
	}

	/** The whole persisted stock state of a tenant: every balance column and the movement count. */
	record Ledger(List<Map<String, Object>> balances, long movements) {
	}

	/** An organization with an owner, a company, a warehouse and a unit product, the base of most scenarios. */
	record Scene(Session owner, UUID company, UUID warehouse, UUID product) {

		String path() {
			return "/api/companies/" + company + "/warehouses/" + warehouse + "/movements";
		}

	}

	/** Registers a user who becomes OWNER of a new organization. */
	Session register() throws Exception {
		String id = UUID.randomUUID().toString().substring(0, 8);
		String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Ana", "email": "%s@example.com", "password": "%s", "organizationName": "Org %s",
				 "organizationSlug": "org-%s"}""".formatted(id, PASSWORD, id, id)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Session("Bearer " + JsonPath.read(body, "$.accessToken"),
				app.queryForObject("select id from users where email = ?", UUID.class, id + "@example.com"),
				app.queryForObject("select id from organizations where slug = ?", UUID.class, "org-" + id));
	}

	/** A second user with the given role in the organization, holding a token for it. */
	Session joinAs(Session of, String role) throws Exception {
		Session user = register();
		ownerJdbc().update(
				"insert into memberships (id, user_id, organization_id, role, created_at) values (?, ?, ?, ?, now())",
				UUID.randomUUID(), user.userId(), of.organizationId(), role);
		String body = mvc.perform(post("/api/auth/switch-organization").header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"organizationId\": \"%s\"}".formatted(of.organizationId())))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Session("Bearer " + JsonPath.read(body, "$.accessToken"), user.userId(), of.organizationId());
	}

	/** A MEMBER of the owner's organization, holding a grant of the given kind ({@code companyId} or {@code warehouseId}). */
	Session memberWithGrant(Session owner, String kind, UUID target) throws Exception {
		Session member = joinAs(owner, "MEMBER");
		mvc.perform(post("/api/members/" + member.userId() + "/access").header("Authorization", owner.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"%s\": \"%s\"}".formatted(kind, target)))
			.andExpect(status().isCreated());
		return member;
	}

	JdbcTemplate ownerJdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
	}

	// RLS is forced on the owner too, so it reads and writes inside the tenant
	void asOwnerIn(UUID tenant, String sql, Object... args) {
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenant.toString());
			owner.update(sql, args);
		}
		finally {
			connection.destroy();
		}
	}

	Ledger ledger(UUID tenant) {
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenant.toString());
			return new Ledger(
					owner.queryForList("select * from stock_balances order by warehouse_id, product_id"),
					owner.queryForObject("select count(*) from stock_movements", Long.class));
		}
		finally {
			connection.destroy();
		}
	}

	void archiveOrganization(UUID organizationId) {
		asOwnerIn(organizationId, "update organizations set archived_at = now() where id = ?", organizationId);
	}

	UUID seedProduct(UUID tenant, String sku, String unit, boolean archived) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, """
				insert into products (id, tenant_id, sku, name, unit, archived_at)
				values (?, ?, ?, ?, ?, case when ? then now() end)""", id, tenant, sku, "Product " + sku, unit, archived);
		return id;
	}

	UUID createProduct(Session as, String sku, String unit) throws Exception {
		return createdId(mvc.perform(post("/api/products").header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"sku\": \"%s\", \"name\": \"Product %s\", \"unit\": \"%s\"}".formatted(sku, sku, unit))));
	}

	UUID createCompany(Session as) throws Exception {
		return createdId(mvc.perform(post("/api/companies").header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Company %s\"}".formatted(UUID.randomUUID()))));
	}

	UUID createWarehouse(Session as, UUID company) throws Exception {
		return createdId(mvc.perform(post("/api/companies/" + company + "/warehouses")
			.header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Warehouse %s\", \"code\": \"W%d\"}".formatted(UUID.randomUUID(), ++codes))));
	}

	private UUID createdId(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(
				result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
	}

	Scene scene() throws Exception {
		Session owner = register();
		UUID company = createCompany(owner);
		return new Scene(owner, company, createWarehouse(owner, company),
				createProduct(owner, "P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "UN"));
	}

	void archive(Session as, String path) throws Exception {
		mvc.perform(post(path + "/archive").header("Authorization", as.bearer())).andExpect(status().isNoContent());
	}

	static String move(String type, Object productId, Object quantity, String note) {
		return "{\"type\": \"%s\", \"productId\": \"%s\", \"quantity\": %s%s}".formatted(type, productId, quantity,
				note == null ? "" : ", \"note\": \"" + note + "\"");
	}

	ResultActions send(Session as, String path, String key, String json) throws Exception {
		MockHttpServletRequestBuilder request = post(path).header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mvc.perform(request);
	}

	/** Records a movement with a fresh key and expects {@code 201}; returns the body. */
	String record(Session as, String path, String json) throws Exception {
		return send(as, path, UUID.randomUUID().toString(), json).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	ResultActions getAs(Session as, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", as.bearer()));
	}

	static BigDecimal number(String json, String path) {
		return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
	}

	/** A transaction of the database owner, left open so the test decides when its locks are released. */
	Connection ownerTransaction() throws SQLException {
		Connection connection = DriverManager.getConnection(url, ownerUser, ownerPassword);
		connection.setAutoCommit(false);
		return connection;
	}

	/**
	 * Waits until exactly this many backends are queued behind the held transaction: they wait for a lock it holds, or
	 * for a backend that does (unrelated waiters of other tests do not count).
	 */
	void awaitLockWaiters(Connection holder, int count) throws Exception {
		int holderPid;
		try (var statement = holder.createStatement(); var rs = statement.executeQuery("select pg_backend_pid()")) {
			rs.next();
			holderPid = rs.getInt(1);
		}
		awaitTrue(() -> app.queryForObject("""
				with recursive queued(pid) as (
				    select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid))
				    union
				    select a.pid from pg_stat_activity a join queued q on q.pid = any(pg_blocking_pids(a.pid)))
				select count(*) from queued""", Integer.class, holderPid) == count, count + " lock waiters");
	}

	void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out waiting for " + what);
			}
			Thread.sleep(20);
		}
	}

}
