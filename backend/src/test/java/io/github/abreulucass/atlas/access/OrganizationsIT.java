package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
	JdbcTemplate app;

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

}
