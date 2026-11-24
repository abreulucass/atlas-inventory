package io.github.abreulucass.atlas.access;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * The organization (tenant) of the current execution. Bound to a scope, never to a thread: it cannot outlive
 * the call that set it (ADR-002).
 */
public final class TenantContext {

	private static final ScopedValue<UUID> TENANT = ScopedValue.newInstance();

	private TenantContext() {
	}

	public static <T> T callAs(UUID organizationId, Callable<T> task) throws Exception {
		return ScopedValue.where(TENANT, organizationId).call(task::call);
	}

	public static void runAs(UUID organizationId, Runnable task) {
		ScopedValue.where(TENANT, organizationId).run(task);
	}

	public static Optional<UUID> current() {
		return TENANT.isBound() ? Optional.of(TENANT.get()) : Optional.empty();
	}

	public static UUID require() {
		return current().orElseThrow(MissingTenantContextException::new);
	}

}
