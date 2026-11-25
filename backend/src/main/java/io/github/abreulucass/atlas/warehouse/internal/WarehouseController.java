package io.github.abreulucass.atlas.warehouse.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.warehouse.WarehouseView;
import io.github.abreulucass.atlas.warehouse.Warehouses;

@RestController
@RequestMapping("/api/companies/{companyId}/warehouses")
class WarehouseController {

	private final Warehouses warehouses;

	WarehouseController(Warehouses warehouses) {
		this.warehouses = warehouses;
	}

	@GetMapping
	List<WarehouseView> list(@PathVariable UUID companyId, @RequestParam(defaultValue = "false") boolean includeArchived) {
		return warehouses.list(companyId, includeArchived);
	}

	@GetMapping("/{id}")
	WarehouseView get(@PathVariable UUID companyId, @PathVariable UUID id) {
		return warehouses.find(companyId, id);
	}

}
