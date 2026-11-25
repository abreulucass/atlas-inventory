package io.github.abreulucass.atlas.warehouse;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.TenantContext;
import io.github.abreulucass.atlas.company.Companies;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WarehouseApiIT {

	static final String PASSWORD = "x1".repeat(8);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate app;

	@Autowired
	Companies companies;

	@Autowired
	Warehouses warehouses;

	@Value("${spring.datasource.url}")
	String url;

	@Value("${spring.flyway.user}")
	String ownerUser;

	@Value("${spring.flyway.password}")
	String ownerPassword;

	record Session(String id, String bearer, UUID userId, UUID organizationId) {
	}

	JdbcTemplate owner() {
		return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url, ownerUser,
				ownerPassword));
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

	UUID seedCompany(Session in, String name) throws Exception {
		return TenantContext.callAs(in.organizationId(), () -> companies.create(name, null));
	}

	UUID seed(Session in, UUID company, String name, String code) throws Exception {
		return TenantContext.callAs(in.organizationId(), () -> warehouses.create(company, name, code));
	}

	// RLS is forced on the owner too, so it seeds inside the tenant it writes to
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

	UUID seedArchived(Session in, UUID company, String name, String code) {
		UUID id = UUID.randomUUID();
		asOwnerIn(in.organizationId(),
				"insert into warehouses (id, tenant_id, company_id, name, code, archived_at) values (?, ?, ?, ?, ?, now())",
				id, in.organizationId(), company, name, code);
		return id;
	}

	String base(UUID company) {
		return "/api/companies/" + company + "/warehouses";
	}

	ResultActions postAs(Session as, String path, String json) throws Exception {
		var request = post(path).header("Authorization", as.bearer());
		return mvc.perform(json == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	ResultActions createAs(Session as, String path, String name, String code) throws Exception {
		return postAs(as, path, "{\"name\": \"%s\", \"code\": \"%s\"}".formatted(name, code));
	}

	/** Rows of the tenant, read as the owner inside that tenant (RLS is forced on it too). */
	int countWarehouses(Session in) {
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class,
					in.organizationId().toString());
			return owner.queryForObject("select count(*) from warehouses", Integer.class);
		}
		finally {
			connection.destroy();
		}
	}

	ResultActions getAs(Session as, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", as.bearer()));
	}

	static ResultActions problem(ResultActions result, int status) throws Exception {
		return result.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(status));
	}

	@Test
	void listReturnsActiveOrderedByName() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");
		UUID zed = seed(owner, company, "Zed", "Z");
		UUID alpha = seed(owner, company, "Alpha", "A");
		seedArchived(owner, company, "Beta", "B");
		Session member = joinAs(owner, "MEMBER");

		getAs(member, base(company)).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(alpha.toString()))
			.andExpect(jsonPath("$[0].companyId").value(company.toString()))
			.andExpect(jsonPath("$[0].name").value("Alpha"))
			.andExpect(jsonPath("$[0].code").value("A"))
			.andExpect(jsonPath("$[0].status").value("ACTIVE"))
			.andExpect(jsonPath("$[1].id").value(zed.toString()))
			.andExpect(jsonPath("$[1].companyId").value(company.toString()))
			.andExpect(jsonPath("$[1].name").value("Zed"))
			.andExpect(jsonPath("$[1].code").value("Z"))
			.andExpect(jsonPath("$[1].status").value("ACTIVE"));
	}

	@Test
	void listIncludesArchivedWhenAsked() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");
		seed(owner, company, "Zed", "Z");
		UUID beta = seedArchived(owner, company, "Beta", "B");
		seed(owner, company, "Alpha", "A");
		Session member = joinAs(owner, "MEMBER");

		getAs(member, base(company) + "?includeArchived=true").andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].name").value("Alpha"))
			.andExpect(jsonPath("$[1].id").value(beta.toString()))
			.andExpect(jsonPath("$[1].companyId").value(company.toString()))
			.andExpect(jsonPath("$[1].name").value("Beta"))
			.andExpect(jsonPath("$[1].code").value("B"))
			.andExpect(jsonPath("$[1].status").value("ARCHIVED"))
			.andExpect(jsonPath("$[2].name").value("Zed"));
	}

	@Test
	void getReturnsOwnWarehouse() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");
		UUID active = seed(owner, company, "Main", "MAIN");
		UUID archived = seedArchived(owner, company, "Old", "OLD");
		Session member = joinAs(owner, "MEMBER");

		getAs(member, base(company) + "/" + active).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(active.toString()))
			.andExpect(jsonPath("$.companyId").value(company.toString()))
			.andExpect(jsonPath("$.name").value("Main"))
			.andExpect(jsonPath("$.code").value("MAIN"))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		getAs(member, base(company) + "/" + archived).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(archived.toString()))
			.andExpect(jsonPath("$.companyId").value(company.toString()))
			.andExpect(jsonPath("$.name").value("Old"))
			.andExpect(jsonPath("$.code").value("OLD"))
			.andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

	@Test
	void listOfOtherTenantUnknownOrMalformedCompanyReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID foreign = seedCompany(other, "Foreign");
		seed(other, foreign, "Main", "A");

		problem(getAs(owner, base(foreign)), 404);
		problem(getAs(owner, base(foreign) + "?includeArchived=true"), 404);
		problem(getAs(owner, base(UUID.randomUUID())), 404);
		problem(getAs(owner, "/api/companies/not-a-uuid/warehouses"), 404);
	}

	@Test
	void getOtherTenantOtherCompanyUnknownOrMalformedReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID company = seedCompany(owner, "Acme");
		UUID sibling = seedCompany(owner, "Beta");
		UUID own = seed(owner, company, "Main", "A");
		UUID foreignCompany = seedCompany(other, "Foreign");
		UUID foreign = seed(other, foreignCompany, "Main", "A");

		problem(getAs(owner, base(company) + "/" + foreign), 404);
		problem(getAs(owner, base(foreignCompany) + "/" + foreign), 404);
		problem(getAs(owner, base(sibling) + "/" + own), 404);
		problem(getAs(owner, base(company) + "/" + UUID.randomUUID()), 404);
		problem(getAs(owner, base(company) + "/not-a-uuid"), 404);
		problem(getAs(owner, base(UUID.randomUUID()) + "/" + own), 404);
		getAs(owner, base(company) + "/" + own).andExpect(status().isOk());
	}

	@Test
	void listNeverShowsOtherCompanyOrTenant() throws Exception {
		Session first = register();
		Session second = register();
		UUID firstCompany = seedCompany(first, "First Co");
		UUID sibling = seedCompany(first, "Sibling Co");
		UUID secondCompany = seedCompany(second, "Second Co");
		seed(first, firstCompany, "First WH", "F");
		seedArchived(first, firstCompany, "First Archived", "FA");
		seed(first, sibling, "Sibling WH", "S");
		seed(second, secondCompany, "Second WH", "F");

		getAs(first, base(firstCompany) + "?includeArchived=true").andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[?(@.companyId != '%s')]".formatted(firstCompany)).isEmpty())
			.andExpect(jsonPath("$[?(@.name == 'Second WH' || @.name == 'Sibling WH')]").isEmpty());
		getAs(second, base(secondCompany) + "?includeArchived=true").andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("Second WH"))
			.andExpect(jsonPath("$[0].companyId").value(secondCompany.toString()));
	}

	@Test
	void unauthenticatedReturns401() throws Exception {
		UUID id = UUID.randomUUID();

		mvc.perform(get(base(id))).andExpect(status().isUnauthorized());
		mvc.perform(get(base(id) + "/" + id)).andExpect(status().isUnauthorized());
	}

	@Test
	void createReturns201WithLocation() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");
		UUID company = seedCompany(owner, "Acme");
		Session[] creators = { owner, admin };

		for (int i = 0; i < creators.length; i++) {
			String name = "Main " + i;
			String code = "WH-" + i;
			var result = createAs(creators[i], base(company), name, code).andExpect(status().isCreated())
				.andExpect(jsonPath("$.companyId").value(company.toString()))
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andReturn();
			String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
			assertThat(UUID.fromString(id)).isNotNull();
			assertThat(result.getResponse().getHeader("Location")).isEqualTo(base(company) + "/" + id);
			getAs(owner, base(company) + "/" + id).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.name").value(name));
		}
		assertThat(countWarehouses(owner)).isEqualTo(2);
	}

	@Test
	void createNormalizesCode() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");

		var result = createAs(owner, base(company), "Main", " cd-sp ").andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("CD-SP"))
			.andReturn();
		String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		getAs(owner, base(company) + "/" + id).andExpect(jsonPath("$.code").value("CD-SP"));
	}

	@Test
	void createDuplicateNameReturns409() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");
		seed(owner, company, "Main", "A");
		seedArchived(owner, company, "Gone", "B");

		problem(createAs(owner, base(company), "Main", "C"), 409);
		problem(createAs(owner, base(company), "MAIN", "C"), 409);
		problem(createAs(owner, base(company), "gone", "D"), 409);
		assertThat(countWarehouses(owner)).isEqualTo(2);
	}

	@Test
	void createDuplicateCodeReturns409() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");
		seed(owner, company, "Main", "A");
		seedArchived(owner, company, "Gone", "B");

		problem(createAs(owner, base(company), "One", "A"), 409);
		problem(createAs(owner, base(company), "Two", " a "), 409);
		problem(createAs(owner, base(company), "Three", "b"), 409);
		assertThat(countWarehouses(owner)).isEqualTo(2);
	}

	@Test
	void sameNameAndCodeInAnotherCompanyReturns201() throws Exception {
		Session owner = register();
		UUID first = seedCompany(owner, "Acme");
		UUID second = seedCompany(owner, "Beta");
		seed(owner, first, "Main", "A");

		createAs(owner, base(second), "Main", "A").andExpect(status().isCreated())
			.andExpect(jsonPath("$.companyId").value(second.toString()))
			.andExpect(jsonPath("$.name").value("Main"))
			.andExpect(jsonPath("$.code").value("A"))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		assertThat(countWarehouses(owner)).isEqualTo(2);
	}

	@Test
	void createInvalidNameReturns400() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");

		problem(createAs(owner, base(company), "", "A"), 400);
		problem(createAs(owner, base(company), "   ", "A"), 400);
		problem(createAs(owner, base(company), "x".repeat(121), "A"), 400);
		problem(postAs(owner, base(company), "{\"code\": \"A\"}"), 400);
		assertThat(countWarehouses(owner)).isZero();
	}

	@Test
	void createInvalidCodeReturns400() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");

		problem(createAs(owner, base(company), "Main", ""), 400);
		problem(createAs(owner, base(company), "Main", "   "), 400);
		problem(createAs(owner, base(company), "Main", "A".repeat(21)), 400);
		problem(createAs(owner, base(company), "Main", "CD_SP"), 400);
		problem(postAs(owner, base(company), "{\"name\": \"Main\"}"), 400);
		assertThat(countWarehouses(owner)).isZero();
	}

	@Test
	void createInArchivedCompanyReturns409() throws Exception {
		Session owner = register();
		UUID company = UUID.randomUUID();
		asOwnerIn(owner.organizationId(),
				"insert into companies (id, tenant_id, name, archived_at) values (?, ?, 'Gone', now())", company,
				owner.organizationId());

		problem(createAs(owner, base(company), "Main", "A"), 409);
		assertThat(countWarehouses(owner)).isZero();
	}

	@Test
	void memberCannotCreate() throws Exception {
		Session owner = register();
		Session member = joinAs(owner, "MEMBER");
		UUID company = seedCompany(owner, "Acme");

		problem(createAs(member, base(company), "Main", "A"), 403);
		assertThat(countWarehouses(owner)).isZero();
	}

	@Test
	void createInArchivedOrganizationReturns409() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");
		owner().update("update organizations set archived_at = now() where id = ?", owner.organizationId());

		problem(createAs(owner, base(company), "Main", "A"), 409);
		assertThat(countWarehouses(owner)).isZero();
	}

	@Test
	void createInOtherTenantUnknownOrMalformedCompanyReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID foreign = seedCompany(other, "Foreign");

		problem(createAs(owner, base(foreign), "Main", "A"), 404);
		problem(createAs(owner, base(UUID.randomUUID()), "Main", "A"), 404);
		problem(createAs(owner, "/api/companies/not-a-uuid/warehouses", "Main", "A"), 404);
		assertThat(countWarehouses(owner)).isZero();
		assertThat(countWarehouses(other)).isZero();
	}

	@Test
	void demotedUserLosesWriteImmediately() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");
		UUID company = seedCompany(owner, "Acme");
		createAs(admin, base(company), "Before", "B").andExpect(status().isCreated());

		owner().update("update memberships set role = 'MEMBER' where user_id = ? and organization_id = ?",
				admin.userId(), owner.organizationId());

		problem(createAs(admin, base(company), "After", "A"), 403);
		assertThat(countWarehouses(owner)).isEqualTo(1);
	}

	@Test
	void concurrentCreatesYieldOne201() throws Exception {
		Session owner = register();
		UUID company = seedCompany(owner, "Acme");
		int threads = 5;
		var barrier = new java.util.concurrent.CyclicBarrier(threads);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
		try {
			var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
			for (int i = 0; i < threads; i++) {
				String code = "C" + i;
				futures.add(pool.submit(() -> {
					barrier.await();
					return createAs(owner, base(company), "Same", code).andReturn().getResponse().getStatus();
				}));
			}
			var statuses = new java.util.ArrayList<Integer>();
			for (var f : futures) {
				statuses.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
			}
			assertThat(statuses).containsOnly(201, 409);
			assertThat(statuses.stream().filter(c -> c == 201).count()).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(countWarehouses(owner)).isEqualTo(1);
	}

}
