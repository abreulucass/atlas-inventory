package io.github.abreulucass.atlas.catalog;

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
class CatalogSchemaIT {

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

	UUID seedCategory(UUID tenant, String name) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, "insert into categories (id, tenant_id, name) values (?, ?, ?)", id, tenant, name);
		return id;
	}

	UUID seedProduct(UUID tenant, String sku, UUID category) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, "insert into products (id, tenant_id, sku, name, unit, category_id) values (?, ?, ?, 'P', 'UN', ?)",
				id, tenant, sku, category);
		return id;
	}

	<T> T inTenant(UUID tenant, Supplier<T> work) throws Exception {
		return TenantContext.callAs(tenant, () -> new TransactionTemplate(transactionManager).execute(s -> work.get()));
	}

	@BeforeEach
	void seed() {
		orgA = seedOrganization();
		orgB = seedOrganization();
	}

	@Test
	void otherTenantCatalogIsInvisible() throws Exception {
		UUID categoryA = seedCategory(orgA, "A");
		UUID categoryB = seedCategory(orgB, "B");
		UUID productA = seedProduct(orgA, "SKU-A", categoryA);
		UUID productB = seedProduct(orgB, "SKU-B", categoryB);

		List<UUID> categories = inTenant(orgA, () -> app.queryForList("select id from categories", UUID.class));
		List<UUID> products = inTenant(orgA, () -> app.queryForList("select id from products", UUID.class));

		assertThat(categories).contains(categoryA).doesNotContain(categoryB);
		assertThat(products).contains(productA).doesNotContain(productB);
		assertThat(app.queryForList("""
				select relname from pg_class
				where relname in ('categories', 'products') and relrowsecurity and relforcerowsecurity""",
				String.class))
			.containsExactlyInAnyOrder("categories", "products");
	}

	@Test
	void productWithOtherTenantCategoryIsRejected() throws Exception {
		UUID categoryA = seedCategory(orgA, "A");
		UUID categoryB = seedCategory(orgB, "B");
		String insert = "insert into products (id, tenant_id, sku, name, unit, category_id) values (?, ?, ?, 'P', 'UN', ?)";

		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(insert, UUID.randomUUID(), orgA, "X1", categoryB)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("products_category_fk");
		// the tenant's own category, and no category at all, go through
		assertThat(inTenant(orgA, () -> app.update(insert, UUID.randomUUID(), orgA, "X2", categoryA))).isEqualTo(1);
		assertThat(inTenant(orgA, () -> app.update(insert, UUID.randomUUID(), orgA, "X3", null))).isEqualTo(1);
	}

	@Test
	void duplicateSkuAndCategoryNameAreRejected() throws Exception {
		seedCategory(orgA, "Tools");
		seedProduct(orgA, "DUP", null);

		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(
				"insert into categories (id, tenant_id, name) values (?, ?, 'TOOLS')", UUID.randomUUID(), orgA)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("categories_tenant_name_key");
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(
				"insert into products (id, tenant_id, sku, name, unit) values (?, ?, 'DUP', 'P', 'UN')",
				UUID.randomUUID(), orgA)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("products_tenant_sku_key");
		// the same values in another tenant are fine
		seedCategory(orgB, "TOOLS");
		seedProduct(orgB, "DUP", null);
	}

}
