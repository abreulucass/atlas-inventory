package io.github.abreulucass.atlas.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
		return seedArchived(in, name, null);
	}

	UUID seedArchived(Session in, String name, String cnpj) {
		UUID id = UUID.randomUUID();
		owner().execute((ConnectionCallback<Void>) connection -> {
			try (var setting = connection.prepareStatement("select set_config('app.tenant_id', ?, false)");
					var insert = connection.prepareStatement(
							"insert into companies (id, tenant_id, name, cnpj, archived_at) values (?, ?, ?, ?, now())")) {
				setting.setString(1, in.organizationId().toString());
				setting.execute();
				insert.setObject(1, id);
				insert.setObject(2, in.organizationId());
				insert.setString(3, name);
				insert.setString(4, cnpj);
				insert.executeUpdate();
			}
			return null;
		});
		return id;
	}

	ResultActions postAs(Session as, String path, String json) throws Exception {
		var request = post(path).header("Authorization", as.bearer());
		return mvc.perform(json == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	ResultActions createAs(Session as, String name, String cnpj) throws Exception {
		return postAs(as, "/api/companies", cnpj == null ? "{\"name\": \"%s\"}".formatted(name)
				: "{\"name\": \"%s\", \"cnpj\": \"%s\"}".formatted(name, cnpj));
	}

	int countCompanies(Session in) throws Exception {
		return TenantContext.callAs(in.organizationId(), () -> companies.list(true).size());
	}

	static ResultActions problem(ResultActions result, int status) throws Exception {
		return result.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(status));
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
		Session member = joinAs(owner, "MEMBER");

		getAs(member, "/api/companies").andExpect(status().isOk())
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
		Session member = joinAs(owner, "MEMBER");

		getAs(member, "/api/companies?includeArchived=true").andExpect(status().isOk())
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

		problem(getAs(owner, "/api/companies/" + foreign), 404);
		problem(getAs(owner, "/api/companies/" + UUID.randomUUID()), 404);
		problem(getAs(owner, "/api/companies/not-a-uuid"), 404);
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

	@Test
	void createReturns201WithLocation() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");
		String[] cnpjs = { "11.222.333/0001-81", "12.abc.345/01de-35" };
		String[] expected = { "11222333000181", "12ABC34501DE35" };
		Session[] creators = { owner, admin };

		for (int i = 0; i < creators.length; i++) {
			String name = "Acme " + i;
			var result = createAs(creators[i], name, cnpjs[i]).andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.cnpj").value(expected[i]))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andReturn();
			String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
			assertThat(UUID.fromString(id)).isNotNull();
			assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/companies/" + id);
			getAs(owner, "/api/companies/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name));
		}
		assertThat(countCompanies(owner)).isEqualTo(2);
	}

	@Test
	void createDuplicateNameOrCnpjReturns409() throws Exception {
		Session owner = register();
		seed(owner, "Acme", "11222333000181");
		seedArchived(owner, "Gone", "12ABC34501DE35");

		problem(createAs(owner, "Acme", null), 409);
		problem(createAs(owner, "Gone", null), 409);
		problem(createAs(owner, "Fresh", "11222333000181"), 409);
		problem(createAs(owner, "Fresh", "12ABC34501DE35"), 409);
		assertThat(countCompanies(owner)).isEqualTo(2);
	}

	@Test
	void createInvalidInputReturns400() throws Exception {
		Session owner = register();

		problem(createAs(owner, "", null), 400);
		problem(createAs(owner, "   ", null), 400);
		problem(createAs(owner, "x".repeat(121), null), 400);
		problem(createAs(owner, "Acme", "11222333000182"), 400);
		assertThat(countCompanies(owner)).isZero();
	}

	@Test
	void memberCannotCreate() throws Exception {
		Session owner = register();
		Session member = joinAs(owner, "MEMBER");

		problem(createAs(member, "Acme", null), 403);
		assertThat(countCompanies(owner)).isZero();
	}

	@Test
	void createInArchivedOrganizationReturns409() throws Exception {
		Session owner = register();
		owner().update("update organizations set archived_at = now() where id = ?", owner.organizationId());

		problem(createAs(owner, "Acme", null), 409);
		assertThat(countCompanies(owner)).isZero();
	}

	@Test
	void demotedUserLosesWriteImmediately() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");
		createAs(admin, "Before", null).andExpect(status().isCreated());

		owner().update("update memberships set role = 'MEMBER' where user_id = ? and organization_id = ?",
				admin.userId(), owner.organizationId());

		UUID active = seed(owner, "Active", null);
		UUID archived = seedArchived(owner, "Archived");

		createAs(admin, "After", null).andExpect(status().isForbidden());
		postAs(admin, "/api/companies/" + active + "/archive", null).andExpect(status().isForbidden());
		postAs(admin, "/api/companies/" + archived + "/unarchive", null).andExpect(status().isForbidden());

		assertThat(countCompanies(owner)).isEqualTo(3);
		getAs(owner, "/api/companies/" + active).andExpect(jsonPath("$.status").value("ACTIVE"));
		getAs(owner, "/api/companies/" + archived).andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

	@Test
	void archiveReturns204() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");

		for (Session archiver : new Session[] { owner, admin }) {
			UUID id = seed(owner, "Acme " + archiver.id(), null);

			postAs(archiver, "/api/companies/" + id + "/archive", null).andExpect(status().isNoContent());

			getAs(owner, "/api/companies").andExpect(jsonPath("$[?(@.id == '%s')]".formatted(id)).isEmpty());
			getAs(owner, "/api/companies/" + id).andExpect(jsonPath("$.status").value("ARCHIVED"));
		}
		getAs(owner, "/api/companies").andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void repeatedArchiveAndUnarchiveReturn204() throws Exception {
		Session owner = register();
		UUID archived = seedArchived(owner, "Archived");
		UUID active = seed(owner, "Active", null);

		postAs(owner, "/api/companies/" + archived + "/archive", null).andExpect(status().isNoContent());
		postAs(owner, "/api/companies/" + active + "/unarchive", null).andExpect(status().isNoContent());

		getAs(owner, "/api/companies/" + archived).andExpect(jsonPath("$.status").value("ARCHIVED"));
		getAs(owner, "/api/companies/" + active).andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void unarchiveReturns204() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");

		for (Session unarchiver : new Session[] { owner, admin }) {
			UUID id = seedArchived(owner, "Acme " + unarchiver.id());

			postAs(unarchiver, "/api/companies/" + id + "/unarchive", null).andExpect(status().isNoContent());

			getAs(owner, "/api/companies/" + id).andExpect(jsonPath("$.status").value("ACTIVE"));
			getAs(owner, "/api/companies").andExpect(jsonPath("$[?(@.id == '%s')]".formatted(id)).isNotEmpty());
		}
	}

	@Test
	void memberCannotArchiveOrUnarchive() throws Exception {
		Session owner = register();
		Session member = joinAs(owner, "MEMBER");
		UUID active = seed(owner, "Active", null);
		UUID archived = seedArchived(owner, "Archived");

		postAs(member, "/api/companies/" + active + "/archive", null).andExpect(status().isForbidden());
		postAs(member, "/api/companies/" + archived + "/unarchive", null).andExpect(status().isForbidden());

		getAs(owner, "/api/companies/" + active).andExpect(jsonPath("$.status").value("ACTIVE"));
		getAs(owner, "/api/companies/" + archived).andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

	@Test
	void archiveOtherTenantOrUnknownReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID foreign = seed(other, "Acme", null);

		for (String action : new String[] { "archive", "unarchive" }) {
			postAs(owner, "/api/companies/" + foreign + "/" + action, null).andExpect(status().isNotFound());
			postAs(owner, "/api/companies/" + UUID.randomUUID() + "/" + action, null).andExpect(status().isNotFound());
		}
		getAs(other, "/api/companies/" + foreign).andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void archiveInArchivedOrganizationReturns409() throws Exception {
		Session owner = register();
		UUID id = seed(owner, "Acme", null);
		owner().update("update organizations set archived_at = now() where id = ?", owner.organizationId());

		postAs(owner, "/api/companies/" + id + "/archive", null).andExpect(status().isConflict());
		postAs(owner, "/api/companies/" + id + "/unarchive", null).andExpect(status().isConflict());
	}

}
