package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.abreulucass.atlas.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthenticationSchemaIT {

	@Autowired
	JdbcTemplate app;

	UUID user(String email) {
		UUID id = UUID.randomUUID();
		app.update("insert into users (id, name, email, password_hash, created_at) values (?, 'N', ?, 'h', now())", id,
				email);
		return id;
	}

	UUID organization() {
		UUID id = UUID.randomUUID();
		app.update("insert into organizations (id, name, slug) values (?, 'O', ?)", id, "o-" + id);
		return id;
	}

	@Test
	void applicationCannotUpdateUserEmail() {
		UUID id = user(UUID.randomUUID() + "@example.com");

		assertThatThrownBy(() -> app.update("update users set email = 'other@example.com' where id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied");
		assertThat(app.update("update users set name = 'Renamed', password_hash = 'x' where id = ?", id)).isEqualTo(1);
	}

	@Test
	void applicationCannotDeleteUsers() {
		UUID id = user(UUID.randomUUID() + "@example.com");

		assertThatThrownBy(() -> app.update("delete from users where id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied");
	}

	@Test
	void membershipIsUniquePerUserAndOrganization() {
		UUID user = user(UUID.randomUUID() + "@example.com");
		UUID organization = organization();
		String insert = "insert into memberships (id, user_id, organization_id, role, created_at) values (?, ?, ?, 'OWNER', now())";
		app.update(insert, UUID.randomUUID(), user, organization);

		assertThatThrownBy(() -> app.update(insert, UUID.randomUUID(), user, organization))
			.isInstanceOf(DuplicateKeyException.class);
	}

}
