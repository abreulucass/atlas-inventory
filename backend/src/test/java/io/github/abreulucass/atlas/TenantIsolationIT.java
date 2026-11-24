package io.github.abreulucass.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.abreulucass.atlas.access.TenantContext;

/** Native SQL as atlas_app: only the database stands between tenants here (ADR-002). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenantIsolationIT {

	@Autowired
	JdbcTemplate app;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Value("${spring.datasource.url}")
	String url;

	@Value("${spring.flyway.user}")
	String ownerUser;

	@Value("${spring.flyway.password}")
	String ownerPassword;

	UUID orgA;

	UUID orgB;

	UUID companyA;

	UUID companyB;

	JdbcTemplate owner() {
		return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
	}

	UUID seedOrganization() {
		UUID id = UUID.randomUUID();
		owner().update("insert into organizations (id, name, slug) values (?, 'Org', ?)", id, "org-" + id);
		return id;
	}

	// RLS is forced on the owner too, so it seeds inside the tenant it writes to
	UUID seedCompany(UUID tenant) {
		UUID id = UUID.randomUUID();
		owner().execute((ConnectionCallback<Void>) connection -> {
			try (var setting = connection.prepareStatement("select set_config('app.tenant_id', ?, false)");
					var insert = connection
						.prepareStatement("insert into companies (id, tenant_id, name) values (?, ?, ?)")) {
				setting.setString(1, tenant.toString());
				setting.execute();
				insert.setObject(1, id);
				insert.setObject(2, tenant);
				insert.setString(3, "Company " + id);
				insert.executeUpdate();
			}
			return null;
		});
		return id;
	}

	<T> T inTenant(UUID tenant, Supplier<T> work) throws Exception {
		return TenantContext.callAs(tenant, () -> new TransactionTemplate(transactionManager).execute(s -> work.get()));
	}

	@BeforeEach
	void seed() {
		orgA = seedOrganization();
		orgB = seedOrganization();
		companyA = seedCompany(orgA);
		companyB = seedCompany(orgB);
	}

	@Test
	void selectReturnsOnlyCurrentTenantRows() throws Exception {
		List<Map<String, Object>> rows = inTenant(orgA, () -> app.queryForList("select id, tenant_id from companies"));

		assertThat(rows).isNotEmpty().allSatisfy(row -> assertThat(row.get("tenant_id")).isEqualTo(orgA));
		assertThat(rows).extracting(row -> row.get("id")).contains(companyA).doesNotContain(companyB);
	}

	@Test
	void insertForOtherTenantIsRejected() {
		UUID id = UUID.randomUUID();

		assertThatThrownBy(() -> inTenant(orgA,
				() -> app.update("insert into companies (id, tenant_id, name) values (?, ?, 'Intruder')", id, orgB)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("row-level security policy");
	}

	@Test
	void selectWithoutContextReturnsNothing() throws Exception {
		assertThat(inTenant(orgA, () -> app.queryForObject("select count(*) from companies", Integer.class)))
			.isPositive();

		Integer visible = new TransactionTemplate(transactionManager)
			.execute(s -> app.queryForObject("select count(*) from companies", Integer.class));

		assertThat(visible).isZero();
		assertThat(app.queryForObject("select count(*) from companies", Integer.class)).isZero();
	}

	@Test
	void everyTenantTableHasForcedRls() {
		List<Map<String, Object>> tables = app.queryForList("""
				select c.relname, c.relrowsecurity, c.relforcerowsecurity,
				       exists (select 1 from pg_policies p where p.schemaname = 'public' and p.tablename = c.relname) as has_policy
				from pg_class c
				join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'public' and c.relkind in ('r', 'p')
				  and exists (select 1 from pg_attribute a
				              where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped)
				""");

		assertThat(tables).extracting(row -> row.get("relname")).contains("companies");
		assertThat(tables).allSatisfy(row -> assertThat(row).as(String.valueOf(row.get("relname")))
			.containsEntry("relrowsecurity", true)
			.containsEntry("relforcerowsecurity", true)
			.containsEntry("has_policy", true));
	}

}
