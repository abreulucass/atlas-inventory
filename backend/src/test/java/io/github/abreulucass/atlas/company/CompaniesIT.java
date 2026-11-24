package io.github.abreulucass.atlas.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.MissingTenantContextException;
import io.github.abreulucass.atlas.access.OrganizationArchivedException;
import io.github.abreulucass.atlas.access.Organizations;
import io.github.abreulucass.atlas.access.TenantContext;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CompaniesIT {

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

	// RLS is forced on the owner too, so it seeds inside the tenant it writes to
	UUID seedArchived(UUID tenant, String name, String cnpj) {
		UUID id = UUID.randomUUID();
		new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword))
			.execute((ConnectionCallback<Void>) connection -> {
				try (var setting = connection.prepareStatement("select set_config('app.tenant_id', ?, false)");
						var insert = connection.prepareStatement(
								"insert into companies (id, tenant_id, name, cnpj, archived_at) values (?, ?, ?, ?, now())")) {
					setting.setString(1, tenant.toString());
					setting.execute();
					insert.setObject(1, id);
					insert.setObject(2, tenant);
					insert.setString(3, name);
					insert.setString(4, cnpj);
					insert.executeUpdate();
				}
				return null;
			});
		return id;
	}

	/** Rows of the tenant as atlas_app sees them, with RLS. */
	Integer countRows(UUID tenant) throws Exception {
		return as(tenant, () -> new TransactionTemplate(transactionManager)
			.execute(s -> app.queryForObject("select count(*) from companies", Integer.class)));
	}

	@Test
	void createsCompanyWithoutCnpj() throws Exception {
		UUID org = organization();

		UUID id = as(org, () -> companies.create("  Acme  ", null));

		assertThat(id).isNotNull();
		assertThat(as(org, () -> companies.list(false))).containsExactly(new CompanyView(id, "Acme", null, Status.ACTIVE));
	}

	@Test
	void storesCnpjWithoutMask() throws Exception {
		UUID org = organization();

		UUID id = as(org, () -> companies.create("Acme", "12.abc.345/01de-35"));

		assertThat(as(org, () -> companies.list(false)))
			.containsExactly(new CompanyView(id, "Acme", "12ABC34501DE35", Status.ACTIVE));
	}

	@Test
	void rejectsInvalidName() throws Exception {
		UUID org = organization();

		for (String name : List.of("", "   ", " " + "x".repeat(121) + " ", "🌱".repeat(121))) {
			assertThatThrownBy(() -> as(org, () -> companies.create(name, null))).as(name)
				.isInstanceOf(InvalidNameException.class);
		}
		assertThat(countRows(org)).isZero();
		assertThat(as(org, () -> companies.create(" " + "x".repeat(120) + " ", null))).isNotNull();
		UUID supplementary = as(org, () -> companies.create("🌱".repeat(120), null));
		assertThat(as(org, () -> companies.list(false))).filteredOn(view -> view.id().equals(supplementary))
			.extracting(CompanyView::name)
			.containsExactly("🌱".repeat(120));
	}

	@Test
	void rejectsDuplicateNameIgnoringCase() throws Exception {
		UUID org = organization();
		as(org, () -> companies.create("Acme", null));
		seedArchived(org, "Beta", null);

		assertThatThrownBy(() -> as(org, () -> companies.create("  ACME ", null)))
			.isInstanceOf(DuplicateCompanyNameException.class);
		assertThatThrownBy(() -> as(org, () -> companies.create("beta", null)))
			.isInstanceOf(DuplicateCompanyNameException.class);
		assertThat(countRows(org)).isEqualTo(2);
	}

	@Test
	void allowsSameNameInAnotherOrganization() throws Exception {
		UUID first = organization();
		UUID second = organization();
		as(first, () -> companies.create("Acme", null));

		UUID id = as(second, () -> companies.create("Acme", null));

		assertThat(as(second, () -> companies.list(false))).extracting(CompanyView::id).containsExactly(id);
	}

	@Test
	void rejectsDuplicateCnpj() throws Exception {
		UUID org = organization();
		as(org, () -> companies.create("Acme", "11222333000181"));
		seedArchived(org, "Beta", "12ABC34501DE35");

		assertThatThrownBy(() -> as(org, () -> companies.create("Gamma", "11.222.333/0001-81")))
			.isInstanceOf(DuplicateCnpjException.class);
		assertThatThrownBy(() -> as(org, () -> companies.create("Delta", "12abc34501de35")))
			.isInstanceOf(DuplicateCnpjException.class);
		assertThat(countRows(org)).isEqualTo(2);
	}

	@Test
	void rejectsInvalidCnpj() throws Exception {
		UUID org = organization();
		List<String> invalid = List.of(
				// wrong check digit
				"11222333000182", "11.222.333/0001-80", "12ABC34501DE36",
				// wrong length
				"", "1122233300018", "112223330001811", "11.222.333/0001-8",
				// all characters equal
				"00000000000000", "00.000.000/0000-00", "AAAAAAAAAAAAAA",
				// punctuation outside the full mask
				"11222333000181-", "11222333/000181", "112.22.333/0001-81", "11.222.333.0001-81", "11-222-333-0001-81");

		for (String cnpj : invalid) {
			assertThatThrownBy(() -> as(org, () -> companies.create("Acme", cnpj))).as(cnpj)
				.isInstanceOf(InvalidCnpjException.class);
		}
		assertThat(countRows(org)).isZero();
	}

	@Test
	void allowsSameCnpjInAnotherOrganization() throws Exception {
		UUID first = organization();
		UUID second = organization();
		UUID firstId = as(first, () -> companies.create("Acme", "11222333000181"));

		UUID secondId = as(second, () -> companies.create("Acme", "11.222.333/0001-81"));

		assertThat(as(first, () -> companies.list(true)))
			.containsExactly(new CompanyView(firstId, "Acme", "11222333000181", Status.ACTIVE));
		assertThat(as(second, () -> companies.list(true)))
			.containsExactly(new CompanyView(secondId, "Acme", "11222333000181", Status.ACTIVE));
	}

	@Test
	void allowsSeveralCompaniesWithoutCnpj() throws Exception {
		UUID org = organization();

		as(org, () -> companies.create("Acme", null));
		as(org, () -> companies.create("Beta", null));

		assertThat(as(org, () -> companies.list(false))).extracting(CompanyView::name).containsExactly("Acme", "Beta");
	}

	@Test
	void listsActiveCompaniesByName() throws Exception {
		UUID org = organization();
		UUID other = organization();
		as(org, () -> companies.create("Charlie", null));
		as(org, () -> companies.create("Alpha", null));
		as(org, () -> companies.create("Bravo", null));
		seedArchived(org, "Aardvark", null);
		as(other, () -> companies.create("Able", null));

		List<CompanyView> listed = as(org, () -> companies.list(false));

		assertThat(listed).extracting(CompanyView::name).containsExactly("Alpha", "Bravo", "Charlie");
		assertThat(listed).extracting(CompanyView::status).containsOnly(Status.ACTIVE);
	}

	@Test
	void listsArchivedWhenAsked() throws Exception {
		UUID org = organization();
		UUID other = organization();
		UUID charlie = as(org, () -> companies.create("Charlie", null));
		UUID alpha = as(org, () -> companies.create("Alpha", null));
		UUID aardvark = seedArchived(org, "Aardvark", null);
		as(other, () -> companies.create("Able", null));

		List<CompanyView> listed = as(org, () -> companies.list(true));

		assertThat(listed).containsExactly(new CompanyView(aardvark, "Aardvark", null, Status.ARCHIVED),
				new CompanyView(alpha, "Alpha", null, Status.ACTIVE),
				new CompanyView(charlie, "Charlie", null, Status.ACTIVE));
	}

	@Test
	void concurrentDuplicateNamesCreateOne() throws Exception {
		UUID org = organization();
		CyclicBarrier start = new CyclicBarrier(2);
		List<Future<UUID>> results = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			for (int i = 0; i < 2; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return as(org, () -> companies.create("Acme", null));
				}));
			}
		}

		List<UUID> created = new ArrayList<>();
		List<Throwable> failures = new ArrayList<>();
		for (Future<UUID> result : results) {
			try {
				created.add(result.get());
			}
			catch (ExecutionException e) {
				failures.add(e.getCause());
			}
		}

		assertThat(created).hasSize(1);
		assertThat(failures).singleElement().isInstanceOf(DuplicateCompanyNameException.class);
		assertThat(countRows(org)).isEqualTo(1);
	}

	@Test
	void rejectsOperationsWithoutTenantContext() throws Exception {
		UUID org = organization();
		String name = "Orphan " + UUID.randomUUID();

		assertThatThrownBy(() -> companies.create(name, null)).isInstanceOf(MissingTenantContextException.class);
		assertThatThrownBy(() -> companies.list(true)).isInstanceOf(MissingTenantContextException.class);

		assertThat(countRows(org)).isZero();

		UUID id = as(org, () -> companies.create("Acme", null));
		assertThatThrownBy(() -> companies.archive(id)).isInstanceOf(MissingTenantContextException.class);
		assertThatThrownBy(() -> companies.unarchive(id)).isInstanceOf(MissingTenantContextException.class);
		assertThat(status(org, id)).isEqualTo(Status.ACTIVE);
	}

	@Test
	void tenantIdComesFromContext() throws Exception {
		UUID org = organization();
		UUID other = organization();

		UUID id = as(org, () -> companies.create("Acme", null));

		String sql = "select tenant_id from companies where id = ?";
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		List<UUID> seenByOwner = as(org, () -> tx.execute(s -> app.queryForList(sql, UUID.class, id)));
		List<UUID> seenByOther = as(other, () -> tx.execute(s -> app.queryForList(sql, UUID.class, id)));
		assertThat(seenByOwner).containsExactly(org);
		assertThat(seenByOther).isEmpty();
	}

	@Test
	void listsCompaniesOfArchivedOrganization() throws Exception {
		UUID org = organization();
		UUID id = as(org, () -> companies.create("Acme", null));
		organizations.archive(org);

		assertThat(as(org, () -> companies.list(false))).extracting(CompanyView::id).containsExactly(id);
	}

	Status status(UUID tenant, UUID company) throws Exception {
		return as(tenant, () -> companies.list(true)).stream()
			.filter(view -> view.id().equals(company))
			.findFirst()
			.orElseThrow()
			.status();
	}

	Timestamp companyArchivedAt(UUID tenant, UUID company) throws Exception {
		return as(tenant, () -> new TransactionTemplate(transactionManager).execute(s -> app
			.queryForObject("select archived_at from companies where id = ?", Timestamp.class, company)));
	}

	Timestamp organizationArchivedAt(UUID organization) {
		return app.queryForObject("select archived_at from organizations where id = ?", Timestamp.class,
				organization);
	}

	@Test
	void archivesCompany() throws Exception {
		UUID org = organization();
		UUID id = as(org, () -> companies.create("Acme", null));

		as(org, () -> {
			companies.archive(id);
			return null;
		});

		assertThat(status(org, id)).isEqualTo(Status.ARCHIVED);
		assertThat(as(org, () -> companies.list(false))).isEmpty();
	}

	@Test
	void unarchivesCompany() throws Exception {
		UUID org = organization();
		UUID id = seedArchived(org, "Acme", null);

		as(org, () -> {
			companies.unarchive(id);
			return null;
		});

		assertThat(as(org, () -> companies.list(false))).containsExactly(new CompanyView(id, "Acme", null, Status.ACTIVE));
	}

	@Test
	void archiveAndUnarchiveAreIdempotent() throws Exception {
		UUID org = organization();
		UUID id = as(org, () -> companies.create("Acme", null));

		as(org, () -> {
			companies.unarchive(id);
			return null;
		});
		assertThat(status(org, id)).isEqualTo(Status.ACTIVE);
		as(org, () -> {
			companies.archive(id);
			return null;
		});
		Timestamp companyArchived = companyArchivedAt(org, id);
		as(org, () -> {
			companies.archive(id);
			return null;
		});
		assertThat(status(org, id)).isEqualTo(Status.ARCHIVED);
		assertThat(companyArchivedAt(org, id)).isEqualTo(companyArchived);
		as(org, () -> {
			companies.unarchive(id);
			companies.unarchive(id);
			return null;
		});
		assertThat(status(org, id)).isEqualTo(Status.ACTIVE);

		organizations.unarchive(org);
		assertThat(organizations.find(org).status()).isEqualTo(io.github.abreulucass.atlas.access.Status.ACTIVE);
		organizations.archive(org);
		Timestamp organizationArchived = organizationArchivedAt(org);
		organizations.archive(org);
		assertThat(organizations.find(org).status()).isEqualTo(io.github.abreulucass.atlas.access.Status.ARCHIVED);
		assertThat(organizationArchivedAt(org)).isEqualTo(organizationArchived);
		organizations.unarchive(org);
		organizations.unarchive(org);
		assertThat(organizations.find(org).status()).isEqualTo(io.github.abreulucass.atlas.access.Status.ACTIVE);
	}

	@Test
	void rejectsCompanyOfAnotherTenant() throws Exception {
		UUID org = organization();
		UUID other = organization();
		UUID foreign = as(other, () -> companies.create("Foreign", null));
		UUID foreignArchived = seedArchived(other, "Foreign archived", null);

		assertThatThrownBy(() -> as(org, () -> {
			companies.archive(foreign);
			return null;
		})).isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> as(org, () -> {
			companies.unarchive(foreignArchived);
			return null;
		})).isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> as(org, () -> {
			companies.archive(UUID.randomUUID());
			return null;
		})).isInstanceOf(CompanyNotFoundException.class);

		assertThat(status(other, foreign)).isEqualTo(Status.ACTIVE);
		assertThat(status(other, foreignArchived)).isEqualTo(Status.ARCHIVED);
	}

	@Test
	void rejectsWritesInArchivedOrganization() throws Exception {
		UUID org = organization();
		UUID active = as(org, () -> companies.create("Active", null));
		UUID archived = seedArchived(org, "Archived", null);
		organizations.archive(org);
		List<Callable<?>> refused = List.of(() -> companies.create("New", null),
				// the archived state is reported before the input is validated (ARCH-04)
				() -> companies.create("", null), () -> companies.create("x".repeat(121), null),
				() -> companies.create("Invalid", "11222333000182"), () -> companies.create("ACTIVE", null), () -> {
					companies.archive(active);
					return null;
				}, () -> {
					companies.unarchive(archived);
					return null;
				});

		for (Callable<?> write : refused) {
			List<Map<String, Object>> before = rows(org);
			assertThatThrownBy(() -> as(org, write)).isInstanceOf(OrganizationArchivedException.class);
			assertThat(rows(org)).isEqualTo(before);
		}

		assertThat(countRows(org)).isEqualTo(2);
		assertThat(status(org, active)).isEqualTo(Status.ACTIVE);
		assertThat(status(org, archived)).isEqualTo(Status.ARCHIVED);
	}

	/** Every column of every company of the tenant, as atlas_app sees them. */
	List<Map<String, Object>> rows(UUID tenant) throws Exception {
		return as(tenant, () -> new TransactionTemplate(transactionManager)
			.execute(s -> app.queryForList("select * from companies order by id")));
	}

	static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("latch timed out");
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	@Test
	void archivingWaitsForCompanyCreationInFlight() throws Exception {
		UUID org = organization();
		CountDownLatch created = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		String waitingArchive = "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
				+ " and query ilike 'update organizations%'";

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<UUID> creating = executor.submit(() -> as(org,
					() -> new TransactionTemplate(transactionManager).execute(s -> {
						UUID id = companies.create("Raced", null);
						created.countDown();
						// the create transaction stays open, holding the share lock on the organization row
						await(release);
						return id;
					})));
			await(created);
			Future<?> archiving = executor.submit(() -> organizations.archive(org));

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (app.queryForObject(waitingArchive, Integer.class) == 0) {
				assertThat(System.nanoTime()).as("archive waits on the share lock").isLessThan(deadline);
				Thread.sleep(10);
			}
			assertThat(archiving).isNotDone();
			assertThat(organizations.find(org).status()).isEqualTo(io.github.abreulucass.atlas.access.Status.ACTIVE);

			release.countDown();
			UUID raced = creating.get(10, TimeUnit.SECONDS);
			archiving.get(10, TimeUnit.SECONDS);

			assertThat(status(org, raced)).isEqualTo(Status.ACTIVE);
			assertThat(organizations.find(org).status()).isEqualTo(io.github.abreulucass.atlas.access.Status.ARCHIVED);
		}
	}

}
