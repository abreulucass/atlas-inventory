package io.github.abreulucass.atlas.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.MissingTenantContextException;
import io.github.abreulucass.atlas.access.OrganizationArchivedException;
import io.github.abreulucass.atlas.access.Organizations;
import io.github.abreulucass.atlas.access.TenantContext;
import io.github.abreulucass.atlas.company.Companies;
import io.github.abreulucass.atlas.company.CompanyNotFoundException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WarehousesIT {

	@Autowired
	Warehouses warehouses;

	@Autowired
	Companies companies;

	@Autowired
	Organizations organizations;

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

	UUID organization() {
		return organizations.create("Org", "org-" + UUID.randomUUID());
	}

	static <T> T as(UUID tenant, Callable<T> work) throws Exception {
		return TenantContext.callAs(tenant, work);
	}

	interface Action {

		void run() throws Exception;

	}

	static void run(UUID tenant, Action action) throws Exception {
		as(tenant, () -> {
			action.run();
			return null;
		});
	}

	UUID company(UUID org, String name) throws Exception {
		return as(org, () -> companies.create(name, null));
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

	UUID seedArchivedCompany(UUID tenant, String name) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, "insert into companies (id, tenant_id, name, archived_at) values (?, ?, ?, now())", id,
				tenant, name);
		return id;
	}

	UUID seedArchivedWarehouse(UUID tenant, UUID company, String name, String code) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant,
				"insert into warehouses (id, tenant_id, company_id, name, code, archived_at) values (?, ?, ?, ?, ?, now())",
				id, tenant, company, name, code);
		return id;
	}

	Integer countRows(UUID tenant) throws Exception {
		return as(tenant, () -> new TransactionTemplate(transactionManager)
			.execute(s -> app.queryForObject("select count(*) from warehouses", Integer.class)));
	}

	@Test
	void createsAndFindsWarehouse() throws Exception {
		UUID org = organization();
		UUID company = company(org, "Acme");

		UUID id = as(org, () -> warehouses.create(company, "  Main  ", " cd-sp "));

		WarehouseView expected = new WarehouseView(id, company, "Main", "CD-SP", Status.ACTIVE);
		assertThat(as(org, () -> warehouses.find(company, id))).isEqualTo(expected);
		assertThat(as(org, () -> warehouses.list(company, false))).containsExactly(expected);
	}

	@Test
	void rejectsInvalidNameAndCode() throws Exception {
		UUID org = organization();
		UUID company = company(org, "Acme");

		for (String name : List.of("", "   ", "x".repeat(121), "🌱".repeat(121))) {
			assertThatThrownBy(() -> as(org, () -> warehouses.create(company, name, "CD"))).as(name)
				.isInstanceOf(InvalidNameException.class);
		}
		for (String code : List.of("", " ", "A".repeat(21), "CD_SP")) {
			assertThatThrownBy(() -> as(org, () -> warehouses.create(company, "Main", code))).as(code)
				.isInstanceOf(InvalidCodeException.class);
		}
		assertThat(countRows(org)).isZero();
	}

	@Test
	void rejectsDuplicateNameIgnoringCaseArchivedIncluded() throws Exception {
		UUID org = organization();
		UUID company = company(org, "Acme");
		as(org, () -> warehouses.create(company, "Main", "A"));
		seedArchivedWarehouse(org, company, "Old", "B");

		assertThatThrownBy(() -> as(org, () -> warehouses.create(company, " MAIN ", "C")))
			.isInstanceOf(DuplicateWarehouseNameException.class);
		assertThatThrownBy(() -> as(org, () -> warehouses.create(company, "old", "D")))
			.isInstanceOf(DuplicateWarehouseNameException.class);
		assertThat(countRows(org)).isEqualTo(2);
	}

	@Test
	void rejectsDuplicateCodeAfterNormalizationArchivedIncluded() throws Exception {
		UUID org = organization();
		UUID company = company(org, "Acme");
		as(org, () -> warehouses.create(company, "Main", "A"));
		seedArchivedWarehouse(org, company, "Old", "B");

		assertThatThrownBy(() -> as(org, () -> warehouses.create(company, "One", " a ")))
			.isInstanceOf(DuplicateWarehouseCodeException.class);
		assertThatThrownBy(() -> as(org, () -> warehouses.create(company, "Two", "b")))
			.isInstanceOf(DuplicateWarehouseCodeException.class);
		assertThat(countRows(org)).isEqualTo(2);
	}

	@Test
	void allowsSameNameAndCodeInAnotherCompany() throws Exception {
		UUID org = organization();
		UUID first = company(org, "Acme");
		UUID second = company(org, "Beta");
		as(org, () -> warehouses.create(first, "Main", "A"));

		UUID id = as(org, () -> warehouses.create(second, "Main", "A"));

		assertThat(as(org, () -> warehouses.list(second, false))).extracting(WarehouseView::id).containsExactly(id);
		assertThat(as(org, () -> warehouses.list(first, false))).hasSize(1);
	}

	@Test
	void refusesCreateInArchivedCompany() throws Exception {
		UUID org = organization();
		UUID company = seedArchivedCompany(org, "Acme");

		assertThatThrownBy(() -> as(org, () -> warehouses.create(company, "Main", "A")))
			.isInstanceOf(CompanyArchivedException.class);
		assertThat(countRows(org)).isZero();
	}

	@Test
	void refusesCreateInArchivedOrganization() throws Exception {
		UUID org = organization();
		UUID company = company(org, "Acme");
		organizations.archive(org);

		assertThatThrownBy(() -> as(org, () -> warehouses.create(company, "Main", "A")))
			.isInstanceOf(OrganizationArchivedException.class);
		assertThat(countRows(org)).isZero();
	}

	@Test
	void unknownAndOtherTenantCompanyAreNotFound() throws Exception {
		UUID org = organization();
		UUID other = organization();
		UUID foreign = company(other, "Foreign");

		for (UUID company : List.of(UUID.randomUUID(), foreign)) {
			assertThatThrownBy(() -> as(org, () -> warehouses.create(company, "Main", "A")))
				.isInstanceOf(CompanyNotFoundException.class);
			assertThatThrownBy(() -> as(org, () -> warehouses.list(company, false)))
				.isInstanceOf(CompanyNotFoundException.class);
			assertThatThrownBy(() -> as(org, () -> warehouses.find(company, UUID.randomUUID())))
				.isInstanceOf(CompanyNotFoundException.class);
			assertThatThrownBy(() -> run(org, () -> warehouses.archive(company, UUID.randomUUID())))
				.isInstanceOf(CompanyNotFoundException.class);
		}
		assertThat(countRows(org)).isZero();
		assertThat(countRows(other)).isZero();
	}

	@Test
	void listsByNameAndArchivedOnlyWhenAsked() throws Exception {
		UUID org = organization();
		UUID company = company(org, "Acme");
		UUID other = company(org, "Beta");
		UUID charlie = as(org, () -> warehouses.create(company, "Charlie", "C"));
		UUID alpha = as(org, () -> warehouses.create(company, "Alpha", "A"));
		UUID aardvark = seedArchivedWarehouse(org, company, "Aardvark", "AA");
		as(org, () -> warehouses.create(other, "Able", "AB"));

		assertThat(as(org, () -> warehouses.list(company, false))).extracting(WarehouseView::id)
			.containsExactly(alpha, charlie);
		assertThat(as(org, () -> warehouses.list(company, true))).containsExactly(
				new WarehouseView(aardvark, company, "Aardvark", "AA", Status.ARCHIVED),
				new WarehouseView(alpha, company, "Alpha", "A", Status.ACTIVE),
				new WarehouseView(charlie, company, "Charlie", "C", Status.ACTIVE));
	}

	@Test
	void findReturnsArchivedAndRefusesOtherCompanyOrTenant() throws Exception {
		UUID org = organization();
		UUID other = organization();
		UUID company = company(org, "Acme");
		UUID sibling = company(org, "Beta");
		UUID foreignCompany = company(other, "Foreign");
		UUID archived = seedArchivedWarehouse(org, company, "Old", "O");
		UUID foreign = as(other, () -> warehouses.create(foreignCompany, "Main", "A"));

		assertThat(as(org, () -> warehouses.find(company, archived)))
			.isEqualTo(new WarehouseView(archived, company, "Old", "O", Status.ARCHIVED));
		assertThatThrownBy(() -> as(org, () -> warehouses.find(sibling, archived)))
			.isInstanceOf(WarehouseNotFoundException.class);
		assertThatThrownBy(() -> as(org, () -> warehouses.find(company, foreign)))
			.isInstanceOf(WarehouseNotFoundException.class);
		assertThatThrownBy(() -> as(org, () -> warehouses.find(company, UUID.randomUUID())))
			.isInstanceOf(WarehouseNotFoundException.class);
	}

	@Test
	void archivesAndUnarchivesIdempotently() throws Exception {
		UUID org = organization();
		UUID company = company(org, "Acme");
		UUID id = as(org, () -> warehouses.create(company, "Main", "A"));

		for (int i = 0; i < 2; i++) {
			run(org, () -> warehouses.archive(company, id));
			assertThat(as(org, () -> warehouses.find(company, id)).status()).isEqualTo(Status.ARCHIVED);
		}
		assertThat(as(org, () -> warehouses.list(company, false))).isEmpty();
		for (int i = 0; i < 2; i++) {
			run(org, () -> warehouses.unarchive(company, id));
			assertThat(as(org, () -> warehouses.find(company, id)).status()).isEqualTo(Status.ACTIVE);
		}
	}

	@Test
	void archiveRefusesOtherCompanyOrTenantAndArchivedOrganization() throws Exception {
		UUID org = organization();
		UUID other = organization();
		UUID company = company(org, "Acme");
		UUID sibling = company(org, "Beta");
		UUID id = as(org, () -> warehouses.create(company, "Main", "A"));
		UUID foreignCompany = company(other, "Foreign");
		UUID foreign = as(other, () -> warehouses.create(foreignCompany, "Main", "A"));

		assertThatThrownBy(() -> run(org, () -> warehouses.archive(sibling, id)))
			.isInstanceOf(WarehouseNotFoundException.class);
		assertThatThrownBy(() -> run(org, () -> warehouses.archive(company, foreign)))
			.isInstanceOf(WarehouseNotFoundException.class);
		assertThatThrownBy(() -> run(org, () -> warehouses.unarchive(company, UUID.randomUUID())))
			.isInstanceOf(WarehouseNotFoundException.class);
		organizations.archive(org);
		assertThatThrownBy(() -> run(org, () -> warehouses.archive(company, id)))
			.isInstanceOf(OrganizationArchivedException.class);
		assertThat(as(org, () -> warehouses.find(company, id)).status()).isEqualTo(Status.ACTIVE);
		assertThat(as(other, () -> warehouses.find(foreignCompany, foreign)).status()).isEqualTo(Status.ACTIVE);
	}

	@Test
	void archivedCompanyKeepsItsWarehousesAndAllowsArchiveAndUnarchive() throws Exception {
		UUID org = organization();
		UUID company = company(org, "Acme");
		UUID id = as(org, () -> warehouses.create(company, "Main", "A"));

		as(org, () -> {
			companies.archive(company);
			return null;
		});

		assertThat(as(org, () -> warehouses.find(company, id)).status()).isEqualTo(Status.ACTIVE);
		run(org, () -> warehouses.archive(company, id));
		assertThat(as(org, () -> warehouses.find(company, id)).status()).isEqualTo(Status.ARCHIVED);
		run(org, () -> warehouses.unarchive(company, id));
		assertThat(as(org, () -> warehouses.find(company, id)).status()).isEqualTo(Status.ACTIVE);
	}

	@Test
	void rejectsOperationsWithoutTenantContext() throws Exception {
		UUID company = UUID.randomUUID();
		UUID id = UUID.randomUUID();

		assertThatThrownBy(() -> warehouses.create(company, "Main", "A")).isInstanceOf(MissingTenantContextException.class);
		assertThatThrownBy(() -> warehouses.list(company, true)).isInstanceOf(MissingTenantContextException.class);
		assertThatThrownBy(() -> warehouses.find(company, id)).isInstanceOf(MissingTenantContextException.class);
		assertThatThrownBy(() -> warehouses.archive(company, id)).isInstanceOf(MissingTenantContextException.class);
		assertThatThrownBy(() -> warehouses.unarchive(company, id)).isInstanceOf(MissingTenantContextException.class);
	}

}
