package io.github.abreulucass.atlas.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.TenantContext;

/** Native SQL as atlas_app: only the database stands between tenants here (ADR-002). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WarehouseSchemaIT {

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

	// RLS is forced on the owner too, so it writes inside the tenant it seeds
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

	UUID seedOrganization() {
		UUID id = UUID.randomUUID();
		asOwnerIn(id, "insert into organizations (id, name, slug) values (?, 'Org', ?)", id, "org-" + id);
		return id;
	}

	UUID seedCompany(UUID tenant) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, "insert into companies (id, tenant_id, name) values (?, ?, ?)", id, tenant, "Company " + id);
		return id;
	}

	UUID seedWarehouse(UUID tenant, UUID company) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, "insert into warehouses (id, tenant_id, company_id, name, code) values (?, ?, ?, ?, 'CD')",
				id, tenant, company, "Warehouse " + id);
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
	void warehouseWithOtherTenantCompanyIsRejected() throws Exception {
		String insert = "insert into warehouses (id, tenant_id, company_id, name, code) values (?, ?, ?, 'W', 'CD')";

		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(insert, UUID.randomUUID(), orgA, companyB)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("warehouses_company_fk");
		// the same statement with the tenant's own company goes through
		assertThat(inTenant(orgA, () -> app.update(insert, UUID.randomUUID(), orgA, companyA))).isEqualTo(1);
	}

	@Test
	void otherTenantWarehousesAreInvisible() throws Exception {
		UUID warehouseA = seedWarehouse(orgA, companyA);
		UUID warehouseB = seedWarehouse(orgB, companyB);

		List<UUID> visible = inTenant(orgA, () -> app.queryForList("select id from warehouses", UUID.class));

		assertThat(visible).contains(warehouseA).doesNotContain(warehouseB);
		assertThat(app.queryForObject(
				"select relrowsecurity and relforcerowsecurity from pg_class where relname = 'warehouses'",
				Boolean.class))
			.isTrue();
	}

}
