package io.github.abreulucass.atlas.warehouse.internal;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import io.github.abreulucass.atlas.access.Permissions;
import io.github.abreulucass.atlas.warehouse.WarehouseView;
import io.github.abreulucass.atlas.warehouse.Warehouses;

@RestController
@RequestMapping("/api/companies/{companyId}/warehouses")
class WarehouseController {

	private final Warehouses warehouses;

	private final Permissions permissions;

	WarehouseController(Warehouses warehouses, Permissions permissions) {
		this.warehouses = warehouses;
		this.permissions = permissions;
	}

	record CreateWarehouse(@NotBlank String name, @NotBlank String code) {
	}

	@GetMapping
	List<WarehouseView> list(@PathVariable UUID companyId, @RequestParam(defaultValue = "false") boolean includeArchived) {
		return warehouses.list(companyId, includeArchived);
	}

	@GetMapping("/{id}")
	WarehouseView get(@PathVariable UUID companyId, @PathVariable UUID id) {
		return warehouses.find(companyId, id);
	}

	@PostMapping
	ResponseEntity<WarehouseView> create(@PathVariable UUID companyId, @Valid @RequestBody CreateWarehouse request) {
		permissions.requireManager();
		UUID id = warehouses.create(companyId, request.name(), request.code());
		return ResponseEntity.created(URI.create("/api/companies/" + companyId + "/warehouses/" + id))
			.body(warehouses.find(companyId, id));
	}

	@PostMapping("/{id}/archive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void archive(@PathVariable UUID companyId, @PathVariable UUID id) {
		permissions.requireManager();
		warehouses.archive(companyId, id);
	}

	@PostMapping("/{id}/unarchive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unarchive(@PathVariable UUID companyId, @PathVariable UUID id) {
		permissions.requireManager();
		warehouses.unarchive(companyId, id);
	}

}
