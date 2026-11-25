package io.github.abreulucass.atlas.company;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.TenantContext;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CompanyApiIT {

	static final String PASSWORD = "x1".repeat(8);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate app;

	@Autowired
	Companies companies;

	@Value("${spring.datasource.url}")
	String url;

	@Value("${spring.flyway.user}")
	String ownerUser;

	@Value("${spring.flyway.password}")
	String ownerPassword;

	record Session(String id, String bearer, UUID userId, UUID organizationId) {
	}

	JdbcTemplate owner() {
		return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
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
		return new Session(id, "Bearer " + JsonPath.read(body, "$.accessToken"),
				app.queryForObject("select id from users where email = ?", UUID.class, id + "@example.com"),
				app.queryForObject("select id from organizations where slug = ?", UUID.class, "org-" + id));
	}

	/** A second user with the given role in the organization, holding a token for it. */
	Session joinAs(Session of, String role) throws Exception {
		Session user = register();
		owner().update(
				"insert into memberships (id, user_id, organization_id, role, created_at) values (?, ?, ?, ?, now())",
				UUID.randomUUID(), user.userId(), of.organizationId(), role);
		String body = mvc.perform(post("/api/auth/switch-organization").header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"organizationId\": \"%s\"}".formatted(of.organizationId())))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Session(user.id(), "Bearer " + JsonPath.read(body, "$.accessToken"), user.userId(),
				of.organizationId());
	}

	UUID seed(Session in, String name, String cnpj) throws Exception {
		return TenantContext.callAs(in.organizationId(), () -> companies.create(name, cnpj));
	}

	// RLS is forced on the owner too, so it seeds inside the tenant it writes to
	UUID seedArchived(Session in, String name) {
		UUID id = UUID.randomUUID();
		owner().execute((ConnectionCallback<Void>) connection -> {
			try (var setting = connection.prepareStatement("select set_config('app.tenant_id', ?, false)");
					var insert = connection.prepareStatement(
							"insert into companies (id, tenant_id, name, archived_at) values (?, ?, ?, now())")) {
				setting.setString(1, in.organizationId().toString());
				setting.execute();
				insert.setObject(1, id);
				insert.setObject(2, in.organizationId());
				insert.setString(3, name);
				insert.executeUpdate();
			}
			return null;
		});
		return id;
	}

	ResultActions getAs(Session as, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", as.bearer()));
	}

	@Test
	void listReturnsActiveOrderedByName() throws Exception {
		Session owner = register();
		UUID zed = seed(owner, "Zed", null);
		UUID acme = seed(owner, "Acme", "12ABC34501DE35");
		seedArchived(owner, "Beta");

		getAs(owner, "/api/companies").andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(acme.toString()))
			.andExpect(jsonPath("$[0].name").value("Acme"))
			.andExpect(jsonPath("$[0].cnpj").value("12ABC34501DE35"))
			.andExpect(jsonPath("$[0].status").value("ACTIVE"))
			.andExpect(jsonPath("$[1].id").value(zed.toString()))
			.andExpect(jsonPath("$[1].cnpj").value((Object) null))
			.andExpect(jsonPath("$[1].status").value("ACTIVE"));
	}

	@Test
	void listIncludesArchivedWhenAsked() throws Exception {
		Session owner = register();
		seed(owner, "Zed", null);
		UUID beta = seedArchived(owner, "Beta");
		seed(owner, "Acme", null);

		getAs(owner, "/api/companies?includeArchived=true").andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].name").value("Acme"))
			.andExpect(jsonPath("$[1].id").value(beta.toString()))
			.andExpect(jsonPath("$[1].status").value("ARCHIVED"))
			.andExpect(jsonPath("$[2].name").value("Zed"));
	}

	@Test
	void getReturnsOwnCompany() throws Exception {
		Session owner = register();
		UUID active = seed(owner, "Acme", null);
		UUID archived = seedArchived(owner, "Beta");
		Session member = joinAs(owner, "MEMBER");

		getAs(member, "/api/companies/" + active).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(active.toString()))
			.andExpect(jsonPath("$.name").value("Acme"))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		getAs(member, "/api/companies/" + archived).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Beta"))
			.andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

	@Test
	void getOtherTenantUnknownOrMalformedReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID foreign = seed(other, "Acme", null);

		getAs(owner, "/api/companies/" + foreign).andExpect(status().isNotFound());
		getAs(owner, "/api/companies/" + UUID.randomUUID()).andExpect(status().isNotFound());
		getAs(owner, "/api/companies/not-a-uuid").andExpect(status().isNotFound());
	}

	@Test
	void listNeverShowsOtherTenant() throws Exception {
		Session first = register();
		Session second = register();
		seed(first, "First Co", null);
		seedArchived(first, "First Archived");
		seed(second, "Second Co", null);

		getAs(first, "/api/companies?includeArchived=true").andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[?(@.name == 'Second Co')]").isEmpty());
		getAs(second, "/api/companies?includeArchived=true").andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("Second Co"));
	}

}
