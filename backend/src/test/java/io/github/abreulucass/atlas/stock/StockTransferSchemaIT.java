package io.github.abreulucass.atlas.stock;

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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.TenantContext;

/** Native SQL as atlas_app: only the database stands between tenants and the transfer ledger (ADR-002). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockTransferSchemaIT {

	static final String INSERT_TRANSFER = """
			insert into stock_transfers (id, tenant_id, from_company_id, from_warehouse_id, to_company_id,
			                             to_warehouse_id, idempotency_key, created_by)
			values (?, ?, ?, ?, ?, ?, ?, ?)""";

	static final String INSERT_ITEM = """
			insert into stock_transfer_items (tenant_id, transfer_id, line, product_id, quantity)
			values (?, ?, 1, ?, 2)""";

	static final String INSERT_MOVEMENT = """
			insert into stock_movements (id, tenant_id, company_id, warehouse_id, product_id, type, quantity,
			                             balance_after, idempotency_key, transfer_id, created_by)
			values (?, ?, ?, ?, ?, ?, 5, 5, ?, ?, ?)""";

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

	UUID warehouseA1;

	UUID warehouseA2;

	UUID productA;

	UUID companyB;

	UUID warehouseB;

	UUID transferA;

	UUID transferB;

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

	UUID seedTransfer(UUID tenant, UUID company, UUID from, UUID to, String key) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, INSERT_TRANSFER, id, tenant, company, from, company, to, key, userId);
		return id;
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
		warehouseA1 = seedWarehouse(orgA, companyA);
		warehouseA2 = seedWarehouse(orgA, companyA);
		warehouseB = seedWarehouse(orgB, companyB);
		productA = seedProduct(orgA);
		UUID warehouseB2 = seedWarehouse(orgB, companyB);
		transferA = seedTransfer(orgA, companyA, warehouseA1, warehouseA2, "key-a");
		transferB = seedTransfer(orgB, companyB, warehouseB, warehouseB2, "key-b");
		asOwnerIn(orgA, INSERT_ITEM, orgA, transferA, productA);
	}

	@Test
	void transferTablesAreTenantIsolated() throws Exception {
		List<UUID> transfers = inTenant(orgA, () -> app.queryForList("select id from stock_transfers", UUID.class));
		List<UUID> items = inTenant(orgA,
				() -> app.queryForList("select transfer_id from stock_transfer_items", UUID.class));

		assertThat(transfers).containsExactly(transferA);
		assertThat(items).containsExactly(transferA);
		assertThat(inTenant(orgB, () -> app.queryForList("select transfer_id from stock_transfer_items", UUID.class))).isEmpty();
		assertThat(app.queryForList("""
				select relname from pg_class
				where relname in ('stock_transfers', 'stock_transfer_items') and relrowsecurity and relforcerowsecurity""",
				String.class))
			.containsExactlyInAnyOrder("stock_transfers", "stock_transfer_items");
		// a row of another tenant cannot be written
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_TRANSFER, UUID.randomUUID(), orgB, companyB,
				warehouseB, companyB, warehouseB, "key-x", userId)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("row-level security policy");
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_ITEM, orgB, transferB, productA)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("row-level security policy");
	}

	@Test
	void transfersAreImmutableForApp() {
		for (String sql : List.of("update stock_transfers set note = 'x'", "delete from stock_transfers",
				"update stock_transfer_items set quantity = 9", "delete from stock_transfer_items")) {
			assertThatThrownBy(() -> inTenant(orgA, () -> app.update(sql))).as(sql)
				.isInstanceOf(DataAccessException.class)
				.rootCause()
				.hasMessageContaining("permission denied");
		}
	}

	@Test
	void crossTenantWarehouseIsRejected() throws Exception {
		// origin of another organization, destination of another organization, and the same warehouse on both ends
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_TRANSFER, UUID.randomUUID(), orgA, companyB,
				warehouseB, companyA, warehouseA2, "key-1", userId)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_transfers_from_fk");
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_TRANSFER, UUID.randomUUID(), orgA, companyA,
				warehouseA1, companyB, warehouseB, "key-2", userId)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_transfers_to_fk");
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_TRANSFER, UUID.randomUUID(), orgA, companyA,
				warehouseA1, companyA, warehouseA1, "key-3", userId)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_transfers_distinct");
		assertThat(inTenant(orgA, () -> app.update(INSERT_TRANSFER, UUID.randomUUID(), orgA, companyA, warehouseA1,
				companyA, warehouseA2, "key-ok", userId)))
			.isEqualTo(1);
	}

	@Test
	void itemForeignKeysAreEnforcedIndependently() throws Exception {
		UUID productB = seedProduct(orgB);
		UUID freshTransferA = seedTransfer(orgA, companyA, warehouseA1, warehouseA2, "key-a2");
		// the row's own tenant_id is the session's, so RLS passes and only the FK on the varied column can fail
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_ITEM, orgA, transferB, productA)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_transfer_items_tenant_id_transfer_id_fkey");
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_ITEM, orgA, freshTransferA, productB)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_transfer_items_tenant_id_product_id_fkey");
		assertThat(inTenant(orgA, () -> app.update(INSERT_ITEM, orgA, freshTransferA, productA))).isEqualTo(1);
	}

	@Test
	void movementOriginCheckSeparatesKeyedFromTransferMovements() throws Exception {
		asOwnerIn(orgA, """
				insert into stock_balances (tenant_id, company_id, warehouse_id, product_id, sku, unit, quantity)
				values (?, ?, ?, ?, 'SKU', 'UN', 5)""", orgA, companyA, warehouseA1, productA);
		// a transfer movement needs its transfer and no key; a keyed movement needs no transfer and a plain type
		for (Object[] bad : List.of(
				new Object[] { "TRANSFER_OUT", "key-m", transferA }, new Object[] { "TRANSFER_OUT", null, null },
				new Object[] { "IN", null, null }, new Object[] { "IN", "key-m", transferA },
				new Object[] { "IN", null, transferA }, new Object[] { "TRANSFER_IN", "key-m", null })) {
			assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_MOVEMENT, UUID.randomUUID(), orgA,
					companyA, warehouseA1, productA, bad[0], bad[1], bad[2], userId)))
				.isInstanceOf(DataAccessException.class)
				.rootCause()
				.hasMessageContaining("stock_movements_origin_check");
		}
		assertThat(inTenant(orgA, () -> app.update(INSERT_MOVEMENT, UUID.randomUUID(), orgA, companyA, warehouseA1,
				productA, "TRANSFER_OUT", null, transferA, userId)))
			.isEqualTo(1);
		assertThat(inTenant(orgA, () -> app.update(INSERT_MOVEMENT, UUID.randomUUID(), orgA, companyA, warehouseA1,
				productA, "IN", "key-m", null, userId)))
			.isEqualTo(1);
		// the transfer link stays inside the tenant
		assertThatThrownBy(() -> inTenant(orgA, () -> app.update(INSERT_MOVEMENT, UUID.randomUUID(), orgA, companyA,
				warehouseA1, productA, "TRANSFER_IN", null, transferB, userId)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("stock_movements_transfer_fk");
	}

}
