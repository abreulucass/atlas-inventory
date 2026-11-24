package io.github.abreulucass.atlas.access;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The organization (tenant) of the current execution. Bound to a scope, never to a thread: it cannot outlive
 * the call that set it (ADR-002).
 */
public final class TenantContext {

	private static final ScopedValue<UUID> TENANT = ScopedValue.newInstance();

	private TenantContext() {
	}

	public static <T> T callAs(UUID organizationId, Callable<T> task) throws Exception {
		requireNoSwitchInTransaction(organizationId);
		return ScopedValue.where(TENANT, organizationId).call(task::call);
	}

	public static void runAs(UUID organizationId, Runnable task) {
		requireNoSwitchInTransaction(organizationId);
		ScopedValue.where(TENANT, organizationId).run(task);
	}

	// The transaction manager applies the context once, when the transaction begins: a switch afterwards would
	// split the Java scope from the database session (TEN-10). Refusing every switch keeps current() equal to the
	// tenant the open transaction applied, including none.
	private static void requireNoSwitchInTransaction(UUID organizationId) {
		if (TransactionSynchronizationManager.isActualTransactionActive()
				&& !current().equals(Optional.of(organizationId))) {
			throw new IllegalStateException("Tenant context cannot change inside a transaction bound to "
					+ current().map(UUID::toString).orElse("no tenant"));
		}
	}

	public static Optional<UUID> current() {
		return TENANT.isBound() ? Optional.of(TENANT.get()) : Optional.empty();
	}

	public static UUID require() {
		return current().orElseThrow(MissingTenantContextException::new);
	}

}
