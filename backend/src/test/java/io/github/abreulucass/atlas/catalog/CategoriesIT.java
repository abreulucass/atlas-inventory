package io.github.abreulucass.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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

import io.github.abreulucass.atlas.OwnerFixture;
import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.MissingTenantContextException;
import io.github.abreulucass.atlas.access.NotAllowedException;
import io.github.abreulucass.atlas.access.OrganizationArchivedException;
import io.github.abreulucass.atlas.access.Organizations;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CategoriesIT {

	@Autowired
	Categories categories;

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

	<T> T as(UUID tenant, Callable<T> work) throws Exception {
		return OwnerFixture.callAsOwner(app, tenant, work);
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

	UUID seedArchivedCategory(UUID tenant, String name) {
		UUID id = UUID.randomUUID();
		asOwnerIn(tenant, "insert into categories (id, tenant_id, name, archived_at) values (?, ?, ?, now())", id,
				tenant, name);
		return id;
	}

	UUID seedMember(UUID tenant, String role) {
		UUID id = UUID.randomUUID();
		app.update("insert into users (id, name, email, password_hash, created_at) values (?, 'M', ?, 'x', now())", id,
				id + "@example.com");
		app.update("insert into memberships (id, user_id, organization_id, role, created_at) values (?, ?, ?, ?, now())",
				UUID.randomUUID(), id, tenant, role);
		return id;
	}

	Integer countRows(UUID tenant) throws Exception {
		return as(tenant, () -> new TransactionTemplate(transactionManager)
			.execute(s -> app.queryForObject("select count(*) from categories", Integer.class)));
	}

	List<Map<String, Object>> rows(UUID tenant) throws Exception {
		return as(tenant, () -> new TransactionTemplate(transactionManager)
			.execute(s -> app.queryForList("select * from categories order by id")));
	}

	@Test
	void createsAndFindsCategory() throws Exception {
		UUID org = organization();

		UUID id = as(org, () -> categories.create("  Tools  "));

		CategoryView expected = new CategoryView(id, "Tools", Status.ACTIVE);
		assertThat(as(org, () -> categories.find(id.toString()))).isEqualTo(expected);
		assertThat(as(org, () -> categories.list(false))).containsExactly(expected);
	}

	@Test
	void rejectsInvalidName() throws Exception {
		UUID org = organization();

		for (String name : java.util.Arrays.asList(null, "", "   ", "x".repeat(81), "🌱".repeat(81))) {
			assertThatThrownBy(() -> as(org, () -> categories.create(name))).as(name)
				.isInstanceOf(InvalidNameException.class);
		}
		assertThat(as(org, () -> categories.create("x".repeat(80)))).isNotNull();
		assertThat(countRows(org)).isEqualTo(1);
	}

	@Test
	void rejectsDuplicateNameIgnoringCaseArchivedIncluded() throws Exception {
		UUID org = organization();
		as(org, () -> categories.create("Tools"));
		seedArchivedCategory(org, "Old");
		List<Map<String, Object>> before = rows(org);

		assertThatThrownBy(() -> as(org, () -> categories.create(" TOOLS ")))
			.isInstanceOf(DuplicateCategoryNameException.class);
		assertThatThrownBy(() -> as(org, () -> categories.create("old")))
			.isInstanceOf(DuplicateCategoryNameException.class);
		assertThat(rows(org)).isEqualTo(before);
	}

	@Test
	void allowsSameNameInAnotherOrganization() throws Exception {
		UUID org = organization();
		UUID other = organization();
		as(org, () -> categories.create("Tools"));

		assertThat(as(other, () -> categories.create("Tools"))).isNotNull();
	}

	@Test
	void listsActiveOrderedByNameIgnoringCaseAndArchivedOnRequest() throws Exception {
		UUID org = organization();
		as(org, () -> categories.create("banana"));
		as(org, () -> categories.create("Apple"));
		as(org, () -> categories.create("cherry"));
		UUID old = seedArchivedCategory(org, "Berry");

		assertThat(as(org, () -> categories.list(false))).extracting(CategoryView::name)
			.containsExactly("Apple", "banana", "cherry");
		assertThat(as(org, () -> categories.list(true))).extracting(CategoryView::name)
			.containsExactly("Apple", "banana", "Berry", "cherry");
		assertThat(as(org, () -> categories.find(old.toString())).status()).isEqualTo(Status.ARCHIVED);
	}

	@Test
	void archivesAndUnarchivesAndRepeatingChangesNothing() throws Exception {
		UUID org = organization();
		UUID id = as(org, () -> categories.create("Tools"));
		List<Map<String, Object>> active = rows(org);

		as(org, () -> {
			categories.unarchive(id.toString());
			return null;
		});
		assertThat(rows(org)).isEqualTo(active);

		as(org, () -> {
			categories.archive(id.toString());
			return null;
		});
		assertThat(as(org, () -> categories.find(id.toString())).status()).isEqualTo(Status.ARCHIVED);
		List<Map<String, Object>> archived = rows(org);
		as(org, () -> {
			categories.archive(id.toString());
			return null;
		});
		assertThat(rows(org)).isEqualTo(archived);

		as(org, () -> {
			categories.unarchive(id.toString());
			return null;
		});
		assertThat(as(org, () -> categories.find(id.toString())).status()).isEqualTo(Status.ACTIVE);
	}

	@Test
	void memberCannotWrite() throws Exception {
		UUID org = organization();
		UUID id = as(org, () -> categories.create("Tools"));
		UUID member = seedMember(org, "MEMBER");
		List<Map<String, Object>> before = rows(org);

		assertThatThrownBy(() -> OwnerFixture.callAs(org, member, () -> categories.create("Other")))
			.isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> OwnerFixture.callAs(org, member, () -> {
			categories.archive(id.toString());
			return null;
		})).isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> OwnerFixture.callAs(org, member, () -> {
			categories.unarchive(id.toString());
			return null;
		})).isInstanceOf(NotAllowedException.class);
		assertThat(rows(org)).isEqualTo(before);
		// reading is open to any member
		assertThat(OwnerFixture.callAs(org, member, () -> categories.list(false))).hasSize(1);
	}

	@Test
	void adminCanWrite() throws Exception {
		UUID org = organization();
		UUID admin = seedMember(org, "ADMIN");

		UUID id = OwnerFixture.callAs(org, admin, () -> categories.create("Tools"));

		assertThat(OwnerFixture.callAs(org, admin, () -> categories.find(id.toString())).name()).isEqualTo("Tools");
	}

	@Test
	void unknownOtherTenantAndMalformedIdsAreNotFound() throws Exception {
		UUID org = organization();
		UUID other = organization();
		UUID foreign = as(other, () -> categories.create("Foreign"));

		for (String id : List.of(UUID.randomUUID().toString(), foreign.toString(), "not-a-uuid", "1-1-1-1-1", "")) {
			assertThatThrownBy(() -> as(org, () -> categories.find(id))).as(id)
				.isInstanceOf(CategoryNotFoundException.class);
			assertThatThrownBy(() -> as(org, () -> {
				categories.archive(id);
				return null;
			})).as(id).isInstanceOf(CategoryNotFoundException.class);
			assertThatThrownBy(() -> as(org, () -> {
				categories.unarchive(id);
				return null;
			})).as(id).isInstanceOf(CategoryNotFoundException.class);
		}
		assertThat(countRows(other)).isEqualTo(1);
	}

	@Test
	void refusesWritesInArchivedOrganization() throws Exception {
		UUID org = organization();
		UUID id = as(org, () -> categories.create("Tools"));
		organizations.archive(org);
		List<Map<String, Object>> before = rows(org);

		assertThatThrownBy(() -> as(org, () -> categories.create("Other")))
			.isInstanceOf(OrganizationArchivedException.class);
		assertThatThrownBy(() -> as(org, () -> {
			categories.archive(id.toString());
			return null;
		})).isInstanceOf(OrganizationArchivedException.class);
		assertThatThrownBy(() -> as(org, () -> {
			categories.unarchive(id.toString());
			return null;
		})).isInstanceOf(OrganizationArchivedException.class);
		assertThat(rows(org)).isEqualTo(before);
	}

	@Test
	void requireAssignableChecksFormatExistenceAndStatus() throws Exception {
		UUID org = organization();
		UUID other = organization();
		UUID active = as(org, () -> categories.create("Tools"));
		UUID archived = seedArchivedCategory(org, "Old");
		UUID foreign = as(other, () -> categories.create("Foreign"));

		assertThat(as(org, () -> categories.requireAssignable(active.toString()).getId())).isEqualTo(active);
		for (String id : List.of("not-a-uuid", "1-1-1-1-1", "")) {
			assertThatThrownBy(() -> as(org, () -> categories.requireAssignable(id))).as(id)
				.isInstanceOf(InvalidCategoryIdException.class);
		}
		for (String id : List.of(UUID.randomUUID().toString(), foreign.toString())) {
			assertThatThrownBy(() -> as(org, () -> categories.requireAssignable(id))).as(id)
				.isInstanceOf(CategoryNotFoundException.class);
		}
		assertThatThrownBy(() -> as(org, () -> categories.requireAssignable(archived.toString())))
			.isInstanceOf(CategoryArchivedException.class);
	}

	@Test
	void failsClosedWithoutTenantContext() {
		assertThatThrownBy(() -> categories.list(false)).isInstanceOf(MissingTenantContextException.class);
	}

}
