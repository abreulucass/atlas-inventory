package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
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

/** Native SQL as atlas_app: only the database stands between tenants here (ADR-002). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MemberAccessSchemaIT {

	static final String INSERT = """
			insert into member_access (id, tenant_id, user_id, company_id, warehouse_id, created_at)
			values (?, ?, ?, ?, ?, now())""";

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

	UUID userA;

	UUID userB;

	// RLS is forced on the owner too, so it writes inside the tenant it seeds
	<T> T asOwnerIn(UUID tenant, java.util.function.Function<JdbcTemplate, T> work) {
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenant.toString());
			return work.apply(owner);
		}
		finally {
			connection.destroy();
		}
	}

	void asOwnerIn(UUID tenant, String sql, Object... args) {
		asOwnerIn(tenant, owner -> owner.update(sql, args));
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

	UUID seedMember(UUID tenant) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, "insert into users (id, name, email, password_hash, created_at) values (?, 'U', ?, 'x', now())",
				id, id + "@example.com");
		asOwnerIn(tenant, """
				insert into memberships (id, user_id, organization_id, role, created_at)
				values (?, ?, ?, 'MEMBER', now())""", UUID.randomUUID(), id, tenant);
		return id;
	}

	UUID seedGrant(UUID tenant, UUID user, UUID company, @Nullable UUID warehouse) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, INSERT, id, tenant, user, company, warehouse);
		return id;
	}

	<T> T inTenant(UUID tenant, Supplier<T> work) throws Exception {
		return TenantContext.callAs(tenant, () -> new TransactionTemplate(transactionManager).execute(s -> work.get()));
	}

	int grantAs(UUID tenant, UUID user, UUID company, @Nullable UUID warehouse) throws Exception {
		return inTenant(tenant, () -> app.update(INSERT, UUID.randomUUID(), tenant, user, company, warehouse));
	}

	@BeforeEach
	void seed() {
		orgA = seedOrganization();
		orgB = seedOrganization();
		companyA = seedCompany(orgA);
		companyB = seedCompany(orgB);
		userA = seedMember(orgA);
		userB = seedMember(orgB);
	}

	@Test
	void grantTargetsAreTenantBound() throws Exception {
		UUID warehouseB = seedWarehouse(orgB, companyB);
		UUID otherCompanyA = seedCompany(orgA);
		UUID otherWarehouseA = seedWarehouse(orgA, otherCompanyA);

		assertThatThrownBy(() -> grantAs(orgA, userA, companyB, null)).isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("member_access_company_fk");
		assertThatThrownBy(() -> grantAs(orgA, userA, companyA, warehouseB)).isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("member_access_warehouse_fk");
		assertThatThrownBy(() -> grantAs(orgA, userA, companyA, otherWarehouseA))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("member_access_warehouse_fk");
		// the tenant's own company and its own warehouse go through
		assertThat(grantAs(orgA, userA, companyA, null)).isEqualTo(1);
		assertThat(grantAs(orgA, userA, otherCompanyA, otherWarehouseA)).isEqualTo(1);
	}

	@Test
	void grantsCascadeWithMembershipAndAreTenantIsolated() throws Exception {
		UUID warehouseA = seedWarehouse(orgA, companyA);
		UUID companyGrant = seedGrant(orgA, userA, companyA, null);
		UUID warehouseGrant = seedGrant(orgA, userA, companyA, warehouseA);
		UUID grantB = seedGrant(orgB, userB, companyB, null);

		List<UUID> visible = inTenant(orgA, () -> app.queryForList("select id from member_access", UUID.class));
		assertThat(visible).contains(companyGrant, warehouseGrant).doesNotContain(grantB);
		assertThat(app.queryForObject(
				"select relrowsecurity and relforcerowsecurity from pg_class where relname = 'member_access'",
				Boolean.class))
			.isTrue();

		int deleted = inTenant(orgA, () -> app.update("delete from memberships where user_id = ? and organization_id = ?",
				userA, orgA));

		assertThat(deleted).isEqualTo(1);
		Integer leftA = asOwnerIn(orgA,
				owner -> owner.queryForObject("select count(*) from member_access where user_id = ?", Integer.class,
						userA));
		List<UUID> leftB = asOwnerIn(orgB,
				owner -> owner.queryForList("select id from member_access where user_id = ?", UUID.class, userB));
		assertThat(leftA).isZero();
		assertThat(leftB).containsExactly(grantB);
	}

	@Test
	void duplicateGrantsAreRejected() throws Exception {
		UUID warehouseA = seedWarehouse(orgA, companyA);
		assertThat(grantAs(orgA, userA, companyA, null)).isEqualTo(1);
		assertThat(grantAs(orgA, userA, companyA, warehouseA)).isEqualTo(1);

		assertThatThrownBy(() -> grantAs(orgA, userA, companyA, null)).isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("member_access_company_key");
		assertThatThrownBy(() -> grantAs(orgA, userA, companyA, warehouseA)).isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("member_access_warehouse_key");
	}

	List<UUID> grantIds(UUID tenant) {
		return asOwnerIn(tenant, owner -> owner.queryForList("select id from member_access", UUID.class));
	}

	/** C36: with every foreign key satisfied for the other tenant, only RLS can stop the write. */
	@Test
	void rlsBlocksCrossTenantInsertAndUpdate() throws Exception {
		UUID foreignGrant = seedGrant(orgB, userB, companyB, null);
		UUID ownGrant = seedGrant(orgA, userA, companyA, null);

		assertThatThrownBy(() -> inTenant(orgA,
				() -> app.update(INSERT, UUID.randomUUID(), orgB, userB, companyB, null)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("row-level security");
		assertThatThrownBy(() -> inTenant(orgA,
				() -> app.update("update member_access set tenant_id = ?, user_id = ?, company_id = ? where id = ?",
						orgB, userB, companyB, ownGrant)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("row-level security");
		// the other tenant's row is invisible to an update from this tenant
		assertThat(inTenant(orgA, () -> app.update("update member_access set created_at = now() where id = ?",
				foreignGrant))).isZero();
		assertThat(grantIds(orgB))
			.containsExactly(foreignGrant);
		assertThat(grantIds(orgA))
			.containsExactly(ownGrant);
	}

	/** C36: in the right tenant, with a valid company, only the membership foreign key can stop the grant. */
	@Test
	void membershipForeignKeyRejectsGrantWithoutMembership() throws Exception {
		UUID withoutAnyMembership = UUID.randomUUID();
		asOwnerIn(orgA, "insert into users (id, name, email, password_hash, created_at) values (?, 'U', ?, 'x', now())",
				withoutAnyMembership, withoutAnyMembership + "@example.com");

		// userB is a member of orgB only
		assertThatThrownBy(() -> grantAs(orgA, userB, companyA, null)).isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("member_access_membership_fk");
		assertThatThrownBy(() -> grantAs(orgA, withoutAnyMembership, companyA, null))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("member_access_membership_fk");
		assertThat(grantIds(orgA)).isEmpty();
	}

}
