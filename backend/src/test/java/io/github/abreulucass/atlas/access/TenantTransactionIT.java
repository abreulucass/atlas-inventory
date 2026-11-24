package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

		// the same holds when the tenant transaction rolls back
		AtomicReference<Integer> rolledBackPid = new AtomicReference<>();
		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> new TransactionTemplate(transactionManager)
			.execute(status -> {
				rolledBackPid.set(app.queryForObject("select pg_backend_pid()", Integer.class));
				assertThat(app.queryForObject("select current_setting('app.tenant_id', true)", String.class))
					.isEqualTo(tenant.toString());
				throw new IllegalStateException("roll back");
			})))
			.hasMessage("roll back");

		assertThat(tenantSetting()).isNull();
		assertThat(rolledBackPid).hasValue(pid);
		assertThat(backendPid()).as("same pooled connection").isEqualTo(pid);
	}

	@Test
	void switchingTenantInsideTransactionIsRejected() throws Exception {
		UUID tenant = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		AtomicBoolean ran = new AtomicBoolean();
		AtomicReference<UUID> sameTenant = new AtomicReference<>();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		TenantContext.callAs(tenant, () -> tx.execute(status -> {
			assertThatThrownBy(() -> TenantContext.callAs(other, () -> ran.getAndSet(true)))
				.isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> TenantContext.runAs(other, () -> ran.set(true)))
				.isInstanceOf(IllegalStateException.class);
			TenantContext.runAs(tenant, () -> sameTenant.set(TenantContext.require()));
			// Java scope and database session still agree on the tenant
			assertThat(TenantContext.require()).isEqualTo(tenant);
			assertThat(app.queryForObject("select nullif(current_setting('app.tenant_id', true), '')", String.class))
				.isEqualTo(tenant.toString());
			return null;
		}));
		// a transaction begun without context is bound to no tenant
		tx.execute(status -> {
			assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> ran.getAndSet(true)))
				.isInstanceOf(IllegalStateException.class);
			return null;
		});

		assertThat(ran).isFalse();
		assertThat(sameTenant).hasValue(tenant);
	}

}
