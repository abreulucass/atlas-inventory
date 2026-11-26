package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.TenantContext;

/** Native SQL as atlas_app: only the database stands between tenants and the ledger here (ADR-002). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockSchemaIT {

	static final String INSERT_MOVEMENT = """
			insert into stock_movements (id, tenant_id, company_id, warehouse_id, product_id, type, quantity,
			                             balance_after, idempotency_key, created_by)
			values (?, ?, ?, ?, ?, 'IN', 5, 5, ?, ?)""";

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

	UUID userId;

	UUID orgA;

	UUID orgB;

	UUID companyA;

	UUID warehouseA;

	UUID productA;

	UUID companyB;

	UUID warehouseB;

	UUID productB;

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
		asOwnerIn(tenant, "insert into warehouses (id, tenant_id, company_id, name, code) values (?, ?, ?, ?, ?)", id,
				tenant, company, "Warehouse " + id, "CD-" + id.toString().substring(0, 8));
		return id;
	}

	UUID seedProduct(UUID tenant) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, "insert into products (id, tenant_id, sku, name, unit) values (?, ?, ?, 'P', 'UN')", id, tenant,
				"SKU-" + id);
		return id;
	}

	void seedBalance(UUID tenant, UUID company, UUID warehouse, UUID product, int quantity) {
		asOwnerIn(tenant, """
				insert into stock_balances (tenant_id, company_id, warehouse_id, product_id, sku, unit, quantity)
				values (?, ?, ?, ?, 'SKU', 'UN', ?)""", tenant, company, warehouse, product, quantity);
	}

	void seedMovement(UUID tenant, UUID company, UUID warehouse, UUID product, String key) {
		asOwnerIn(tenant, INSERT_MOVEMENT, UUID.randomUUID(), tenant, company, warehouse, product, key, userId);
	}

	<T> T inTenant(UUID tenant, Supplier<T> work) throws Exception {
		return TenantContext.callAs(tenant, () -> new TransactionTemplate(transactionManager).execute(s -> work.get()));
	}

	@BeforeEach
	void seed() {
		userId = UUID.randomUUID();
		new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword)).update(
				"insert into users (id, name, email, password_hash, created_at) values (?, 'U', ?, 'x', now())", userId,
				"u-" + userId + "@example.com");
		orgA = seedOrganization();
		orgB = seedOrganization();
		companyA = seedCompany(orgA);
		companyB = seedCompany(orgB);
		warehouseA = seedWarehouse(orgA, companyA);
		warehouseB = seedWarehouse(orgB, companyB);
		productA = seedProduct(orgA);
		productB = seedProduct(orgB);
		seedBalance(orgA, companyA, warehouseA, productA, 5);
		seedBalance(orgB, companyB, warehouseB, productB, 5);
		seedMovement(orgA, companyA, warehouseA, productA, "key-a");
		seedMovement(orgB, companyB, warehouseB, productB, "key-b");
	}

	@Test
	void stockTablesAreTenantIsolated() throws Exception {
		List<UUID> balances = inTenant(orgA,
				() -> app.queryForList("select product_id from stock_balances", UUID.class));
		List<UUID> movements = inTenant(orgA,
				() -> app.queryForList("select product_id from stock_movements", UUID.class));

		assertThat(balances).containsExactly(productA);
		assertThat(movements).containsExactly(productA);
		assertThat(app.queryForList("""
				select relname from pg_class
				where relname in ('stock_balances', 'stock_movements') and relrowsecurity and relforcerowsecurity""",
				String.class))
			.containsExactlyInAnyOrder("stock_balances", "stock_movements");
		// a row of another tenant cannot be written, nor can a balance point at another tenant's warehouse
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_MOVEMENT, UUID.randomUUID(), orgB, companyB,
				warehouseB, productB, "key-x", userId)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("row-level security policy");
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update("""
				insert into stock_balances (tenant_id, company_id, warehouse_id, product_id, sku, unit, quantity)
				values (?, ?, ?, ?, 'SKU', 'UN', 1)""", orgA, companyA, warehouseB, productA)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_balances_warehouse_fk");
		// nor at another tenant's product
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update("""
				insert into stock_balances (tenant_id, company_id, warehouse_id, product_id, sku, unit, quantity)
				values (?, ?, ?, ?, 'SKU', 'UN', 1)""", orgA, companyA, warehouseA, productB)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_balances_product_fk");
		// nor can a movement of this tenant reference a balance that exists only in another tenant
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_MOVEMENT, UUID.randomUUID(), orgA, companyA,
				warehouseB, productB, "key-y", userId)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_movements_balance_fk");
	}

	@Test
	void applicationCannotUpdateOrDeleteMovements() {
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update("update stock_movements set note = 'x'")))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied");
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update("delete from stock_movements")))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied");
	}

	@Test
	void databaseRejectsDuplicateIdempotencyKey() throws Exception {
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_MOVEMENT, UUID.randomUUID(), orgA, companyA,
				warehouseA, productA, "key-a", userId)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_movements_tenant_key_key");
		// the key is scoped to the organization, not the warehouse: another stocked warehouse of the tenant refuses it too
		UUID secondWarehouse = seedWarehouse(orgA, companyA);
		seedBalance(orgA, companyA, secondWarehouse, productA, 5);
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_MOVEMENT, UUID.randomUUID(), orgA, companyA,
				secondWarehouse, productA, "key-a", userId)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_movements_tenant_key_key");
		// the same key in another tenant is fine, as is a new key in this one
		seedMovement(orgB, companyB, warehouseB, productB, "key-a");
		assertThat(inTenant(orgA, () -> app.update(INSERT_MOVEMENT, UUID.randomUUID(), orgA, companyA, warehouseA,
				productA, "key-new", userId)))
			.isEqualTo(1);
	}

	@Test
	void databaseRejectsNegativeBalance() throws Exception {
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(
				"update stock_balances set quantity = -1 where warehouse_id = ? and product_id = ?", warehouseA, productA)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_balances_quantity_check");
		assertThat(inTenant(orgA, () -> app.queryForObject("select quantity from stock_balances", BigDecimal.class)))
			.isEqualByComparingTo("5");
	}

}
