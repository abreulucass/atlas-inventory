package io.github.abreulucass.atlas.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.github.abreulucass.atlas.TestcontainersConfiguration;

/** Users, roles and catalog rows over HTTP and as the database owner, for the catalog API ITs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class CatalogITSupport {

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

	record Session(String bearer, UUID userId, UUID organizationId) {
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

	JdbcTemplate ownerJdbc() {
		return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url, ownerUser,
				ownerPassword));
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

	/** Every column of every row of the table, as the owner inside the tenant: the whole persisted state. */
	List<Map<String, Object>> rows(UUID tenant, String table) {
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenant.toString());
			return owner.queryForList("select * from " + table + " order by id");
		}
		finally {
			connection.destroy();
		}
	}

	void archiveOrganization(UUID organizationId) {
		asOwnerIn(organizationId, "update organizations set archived_at = now() where id = ?", organizationId);
	}

	UUID seedArchivedCategory(UUID tenant, String name) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, "insert into categories (id, tenant_id, name, archived_at) values (?, ?, ?, now())", id,
				tenant, name);
		return id;
	}

	ResultActions postJson(Session as, String path, String json) throws Exception {
		return mvc.perform(post(path).header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	ResultActions getAs(Session as, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", as.bearer()));
	}

	ResultActions postEmpty(Session as, String path) throws Exception {
		return mvc.perform(post(path).header("Authorization", as.bearer()));
	}

	UUID createCategory(Session as, String name) throws Exception {
		String body = postJson(as, "/api/categories", "{\"name\": \"%s\"}".formatted(name))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

}
