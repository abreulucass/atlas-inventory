package io.github.abreulucass.atlas;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Postgres with the same roles as compose.yaml; usernames and passwords come from application.yaml,
 * so the tests connect exactly as the application does.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("atlas")
			.withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/init/01-roles.sql"),
					"/docker-entrypoint-initdb.d/01-roles.sql");
	}

	@Bean
	DynamicPropertyRegistrar postgresUrl(PostgreSQLContainer postgres) {
		return registry -> registry.add("spring.datasource.url", postgres::getJdbcUrl);
	}

}
