package io.github.abreulucass.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseRolesIT {

	@Autowired
	JdbcTemplate app;

	@Value("${spring.datasource.url}")
	String url;

	@Value("${spring.flyway.user}")
	String ownerUser;

	@Value("${spring.flyway.password}")
	String ownerPassword;

	JdbcTemplate owner() {
		return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
	}

	@Test
	void flywayAppliesMigrationsAsOwner() {
		String installedBy = owner().queryForObject(
				"select installed_by from flyway_schema_history where version = '1' and success", String.class);

		assertThat(installedBy).isEqualTo("atlas_owner");
	}

	@Test
	void applicationConnectsAsRoleThatCannotBypassRls() {
		Map<String, Object> role = app.queryForMap(
				"select rolname, rolsuper, rolbypassrls from pg_roles where rolname = current_user");

		assertThat(role).containsEntry("rolname", "atlas_app")
			.containsEntry("rolsuper", false)
			.containsEntry("rolbypassrls", false);
	}

	@Test
	void applicationCannotCreateTables() {
		assertThatThrownBy(() -> app.execute("create table forbidden (id int)"))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied for schema public");
	}

	@Test
	void applicationReadsAndWritesTablesCreatedByOwner() {
		JdbcTemplate owner = owner();
		owner.execute("create table grant_probe (id int primary key, name text not null)");
		try {
			app.update("insert into grant_probe (id, name) values (1, 'written by app')");

			assertThat(app.queryForObject("select name from grant_probe where id = 1", String.class))
				.isEqualTo("written by app");
		}
		finally {
			owner.execute("drop table grant_probe");
		}
	}

}
