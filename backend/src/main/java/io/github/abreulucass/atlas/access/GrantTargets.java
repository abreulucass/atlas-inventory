package io.github.abreulucass.atlas.access;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for the companies and warehouses a member can be granted, in the tenant context's organization. Implemented
 * outside {@code access}, which cannot depend on the modules that depend on it (ADR-001).
 */
public interface GrantTargets {

	/** Whether the company exists in the organization, archived or not. */
	boolean companyExists(UUID companyId);

	/** The company of the warehouse, if the warehouse exists in the organization, archived or not. */
	Optional<UUID> companyOfWarehouse(UUID warehouseId);

}
