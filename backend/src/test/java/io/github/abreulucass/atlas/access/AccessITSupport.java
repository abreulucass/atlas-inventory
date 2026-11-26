package io.github.abreulucass.atlas.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** Users, members, companies, warehouses and grants over HTTP, for the member access ITs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class AccessITSupport {

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

	record Session(String email, String bearer, UUID userId, UUID organizationId) {
	}

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
		return new Session(email, "Bearer " + JsonPath.read(body, "$.accessToken"),
				app.queryForObject("select id from users where email = ?", UUID.class, email),
				app.queryForObject("select id from organizations where slug = ?", UUID.class, "org-" + id));
	}

	Session switchTo(Session user, UUID organizationId) throws Exception {
		String body = mvc.perform(post("/api/auth/switch-organization").header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"organizationId\": \"%s\"}".formatted(organizationId)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Session(user.email(), "Bearer " + JsonPath.read(body, "$.accessToken"), user.userId(),
				organizationId);
	}

	ResultActions addAs(Session as, String email, String role) throws Exception {
		return mvc.perform(post("/api/members").header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"role\": \"%s\"}".formatted(email, role)));
	}

	/** A newly registered user that the manager adds with the role, holding tokens for the manager's organization. */
	Session join(Session manager, String role) throws Exception {
		Session user = register("User");
		addAs(manager, user.email(), role).andExpect(status().isCreated());
		return switchTo(user, manager.organizationId());
	}

	ResultActions changeRoleAs(Session as, Session target, String role) throws Exception {
		return mvc.perform(patch("/api/members/" + target.userId()).header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"role\": \"%s\"}".formatted(role)));
	}

	ResultActions removeAs(Session as, Object userId) throws Exception {
		return mvc.perform(delete("/api/members/" + userId).header("Authorization", as.bearer()));
	}

	UUID company(Session as) throws Exception {
		String body = mvc.perform(post("/api/companies").header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Company %s\"}".formatted(UUID.randomUUID())))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	UUID warehouse(Session as, UUID companyId) throws Exception {
		String body = mvc.perform(post("/api/companies/" + companyId + "/warehouses").header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Warehouse %s\", \"code\": \"W%d\"}".formatted(UUID.randomUUID(), ++codes)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	void archiveCompany(Session as, UUID companyId) throws Exception {
		mvc.perform(post("/api/companies/" + companyId + "/archive").header("Authorization", as.bearer()))
			.andExpect(status().isNoContent());
	}

	void archiveWarehouse(Session as, UUID companyId, UUID warehouseId) throws Exception {
		mvc.perform(post("/api/companies/" + companyId + "/warehouses/" + warehouseId + "/archive")
			.header("Authorization", as.bearer())).andExpect(status().isNoContent());
	}

	ResultActions grantAs(Session as, Object userId, String json) throws Exception {
		return mvc.perform(post("/api/members/" + userId + "/access").header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	ResultActions grantCompanyAs(Session as, Object userId, Object companyId) throws Exception {
		return grantAs(as, userId, "{\"companyId\": \"%s\"}".formatted(companyId));
	}

	ResultActions grantWarehouseAs(Session as, Object userId, Object warehouseId) throws Exception {
		return grantAs(as, userId, "{\"warehouseId\": \"%s\"}".formatted(warehouseId));
	}

	ResultActions readAs(Session as, Object userId) throws Exception {
		return mvc.perform(get("/api/members/" + userId + "/access").header("Authorization", as.bearer()));
	}

	ResultActions revokeCompanyAs(Session as, Object userId, Object companyId) throws Exception {
		return mvc.perform(delete("/api/members/" + userId + "/access/companies/" + companyId)
			.header("Authorization", as.bearer()));
	}

	ResultActions revokeWarehouseAs(Session as, Object userId, Object warehouseId) throws Exception {
		return mvc.perform(delete("/api/members/" + userId + "/access/warehouses/" + warehouseId)
			.header("Authorization", as.bearer()));
	}

	ResultActions getAs(Session as, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", as.bearer()));
	}

	List<String> ids(ResultActions result) throws Exception {
		return JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$[*].id");
	}

	// RLS is forced on the owner too, so it reads inside the tenant
	List<Map<String, Object>> grantRows(UUID tenant) {
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenant.toString());
			return owner.queryForList(
					"select id, user_id, company_id, warehouse_id, created_at from member_access order by id");
		}
		finally {
			connection.destroy();
		}
	}

	void archiveOrganization(UUID organizationId) {
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class,
					organizationId.toString());
			owner.update("update organizations set archived_at = now() where id = ?", organizationId);
		}
		finally {
			connection.destroy();
		}
	}

}
