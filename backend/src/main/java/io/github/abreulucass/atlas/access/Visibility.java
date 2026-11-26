package io.github.abreulucass.atlas.access;

import java.util.Set;
import java.util.UUID;

/**
 * What the current user may read in the tenant context's organization: everything, or a MEMBER's grants. A company
 * grant covers the company and all its warehouses; a warehouse grant covers that warehouse and makes its company
 * readable.
 */
public record Visibility(boolean all, Set<UUID> companies, Set<UUID> warehouses, Set<UUID> warehouseCompanies) {

	static final Visibility ALL = new Visibility(true, Set.of(), Set.of(), Set.of());

	public boolean seesCompany(UUID companyId) {
		return all || companies.contains(companyId) || warehouseCompanies.contains(companyId);
	}

	public boolean seesWarehouse(UUID companyId, UUID warehouseId) {
		return all || companies.contains(companyId) || warehouses.contains(warehouseId);
	}

}
