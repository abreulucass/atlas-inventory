package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.abreulucass.atlas.TestcontainersConfiguration;

// one pooled connection: every transaction reuses the session of the one before it (TEN-06)
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=1")
@Import(TestcontainersConfiguration.class)
class TenantTransactionIT {

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	JdbcTemplate app;

	String tenantSetting() {
		return new TransactionTemplate(transactionManager).execute(status -> app
			.queryForObject("select nullif(current_setting('app.tenant_id', true), '')", String.class));
	}

	Integer backendPid() {
		return new TransactionTemplate(transactionManager)
			.execute(status -> app.queryForObject("select pg_backend_pid()", Integer.class));
	}

	@Test
	void transactionSeesTenantContext() throws Exception {
		UUID tenant = UUID.randomUUID();

		String seen = TenantContext.callAs(tenant, this::tenantSetting);

		assertThat(seen).isEqualTo(tenant.toString());
	}

	@Test
	void contextDoesNotLeakToNextTransaction() throws Exception {
		Integer pid = backendPid();
		UUID tenant = UUID.randomUUID();
		assertThat(TenantContext.callAs(tenant, this::tenantSetting)).isEqualTo(tenant.toString());

		String seen = tenantSetting();

		assertThat(seen).isNull();
		assertThat(backendPid()).as("same pooled connection").isEqualTo(pid);
	}

}
