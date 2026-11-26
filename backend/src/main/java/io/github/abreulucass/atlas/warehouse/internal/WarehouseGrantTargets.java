package io.github.abreulucass.atlas.warehouse.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.github.abreulucass.atlas.access.GrantTargets;
import io.github.abreulucass.atlas.company.Companies;
import io.github.abreulucass.atlas.company.CompanyNotFoundException;

/** Unfiltered by visibility: only a manager, who sees everything, reaches a grant. */
@Component
class WarehouseGrantTargets implements GrantTargets {

	private final Companies companies;

	private final WarehouseRepository warehouses;

	WarehouseGrantTargets(Companies companies, WarehouseRepository warehouses) {
		this.companies = companies;
		this.warehouses = warehouses;
	}

	@Override
	public boolean companyExists(UUID companyId) {
		try {
			companies.find(companyId);
			return true;
		}
		catch (CompanyNotFoundException e) {
			return false;
		}
	}

	@Override
	public Optional<UUID> companyOfWarehouse(UUID warehouseId) {
		return warehouses.findById(warehouseId).map(Warehouse::getCompanyId);
	}

}
