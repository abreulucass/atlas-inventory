package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.abreulucass.atlas.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrganizationsIT {

	@Autowired
	Organizations organizations;

	@Autowired
	JdbcTemplate app;

	static String slug() {
		return "org-" + UUID.randomUUID();
	}

	@Test
	void createsActiveOrganization() {
		String slug = slug();

		UUID id = organizations.create("  Acme  ", slug);

		assertThat(organizations.find(id)).isEqualTo(new OrganizationView(id, "Acme", slug, Status.ACTIVE));
	}

	@Test
	void rejectsDuplicateSlug() {
		String slug = slug();
		UUID first = organizations.create("First", slug);
		Map<String, Object> original = app.queryForMap("select * from organizations where id = ?", first);
		String second = "Second " + slug;

		assertThatThrownBy(() -> organizations.create(second, slug)).isInstanceOf(DuplicateSlugException.class);

		assertThat(app.queryForObject("select count(*) from organizations where slug = ?", Integer.class, slug))
			.isEqualTo(1);
		assertThat(app.queryForMap("select * from organizations where id = ?", first)).isEqualTo(original);
		assertThat(app.queryForObject("select count(*) from organizations where name = ?", Integer.class, second))
			.isZero();
	}

	@Test
	void rejectsInvalidSlug() {
		List<String> invalid = List.of("ab", "a".repeat(64), "Acme", "-acme", "ac--me", "acme-", "ac me");

		for (String slug : invalid) {
			assertThatThrownBy(() -> organizations.create("Acme", slug)).as(slug)
				.isInstanceOf(InvalidSlugException.class);
		}
		assertThat(organizations.create("Acme", "a".repeat(63))).isNotNull();
		assertThat(organizations.create("Acme", "abc")).isNotNull();
	}

	@Test
	void rejectsInvalidName() {
		for (String name : List.of("", "   ", " ".repeat(3) + "x".repeat(121) + " ", "🌱".repeat(121))) {
			assertThatThrownBy(() -> organizations.create(name, slug())).as(name)
				.isInstanceOf(InvalidNameException.class);
		}
		assertThat(organizations.create(" " + "x".repeat(120) + " ", slug())).isNotNull();
		UUID supplementary = organizations.create("🌱".repeat(120), slug());
		assertThat(organizations.find(supplementary).name()).isEqualTo("🌱".repeat(120));
	}

	@Test
	void databaseRejectsSlugUpdate() {
		UUID id = UUID.randomUUID();
		app.update("insert into organizations (id, name, slug) values (?, 'Acme', ?)", id, "slug-" + id);

		assertThatThrownBy(() -> app.update("update organizations set slug = 'renamed' where id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied");
		app.update("update organizations set name = 'Acme 2' where id = ?", id);
	}

	@Test
	void databaseRejectsOrganizationDelete() {
		String slug = slug();
		UUID id = organizations.create("Acme", slug);

		assertThatThrownBy(() -> app.update("delete from organizations where id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied");

		assertThat(organizations.find(id)).isEqualTo(new OrganizationView(id, "Acme", slug, Status.ACTIVE));
		assertThatThrownBy(() -> organizations.create("Other", slug)).isInstanceOf(DuplicateSlugException.class);
	}

	@Test
	void findsOrganizationById() {
		String slug = slug();
		UUID id = organizations.create("Beta", slug);

		OrganizationView view = organizations.find(id);

		assertThat(view.id()).isEqualTo(id);
		assertThat(view.name()).isEqualTo("Beta");
		assertThat(view.slug()).isEqualTo(slug);
		assertThat(view.status()).isEqualTo(Status.ACTIVE);
	}

	@Test
	void rejectsUnknownOrganization() {
		assertThatThrownBy(() -> organizations.find(UUID.randomUUID()))
			.isInstanceOf(OrganizationNotFoundException.class);
		assertThatThrownBy(() -> organizations.archive(UUID.randomUUID()))
			.isInstanceOf(OrganizationNotFoundException.class);
		assertThatThrownBy(() -> organizations.unarchive(UUID.randomUUID()))
			.isInstanceOf(OrganizationNotFoundException.class);
	}

	@Test
	void archivesOrganization() {
		UUID id = organizations.create("Acme", slug());
		assertThat(organizations.find(id).status()).isEqualTo(Status.ACTIVE);

		organizations.archive(id);
		assertThat(organizations.find(id).status()).isEqualTo(Status.ARCHIVED);
		organizations.archive(id);

		assertThat(organizations.find(id).status()).isEqualTo(Status.ARCHIVED);
	}

	@Test
	void unarchivesOrganization() {
		UUID id = organizations.create("Acme", slug());
		organizations.archive(id);
		assertThat(organizations.find(id).status()).isEqualTo(Status.ARCHIVED);

		organizations.unarchive(id);
		assertThat(organizations.find(id).status()).isEqualTo(Status.ACTIVE);
		organizations.unarchive(id);

		assertThat(organizations.find(id).status()).isEqualTo(Status.ACTIVE);
		organizations.requireWritable(id);
	}

	@Test
	void requireWritableRejectsArchived() {
		UUID id = organizations.create("Acme", slug());
		organizations.requireWritable(id);
		organizations.archive(id);

		assertThatThrownBy(() -> organizations.requireWritable(id)).isInstanceOf(OrganizationArchivedException.class);
		assertThatThrownBy(() -> organizations.requireWritable(UUID.randomUUID()))
			.isInstanceOf(OrganizationNotFoundException.class);
	}

}
