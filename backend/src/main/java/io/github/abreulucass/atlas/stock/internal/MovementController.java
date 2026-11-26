package io.github.abreulucass.atlas.stock.internal;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.stock.MovementView;
import io.github.abreulucass.atlas.stock.MovementPage;
import io.github.abreulucass.atlas.stock.Movements;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequestMapping("/api/companies/{companyId}/warehouses/{warehouseId}/movements")
class MovementController {

	private final Movements movements;

	MovementController(Movements movements) {
		this.movements = movements;
	}

	@PostMapping
	ResponseEntity<MovementView> record(@PathVariable String companyId, @PathVariable String warehouseId,
			@Parameter(required = true) @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String key,
			@RequestBody(required = false) @Nullable String body) {
		MovementView view = movements.record(companyId, warehouseId, key, body);
		return ResponseEntity
			.created(URI.create("/api/companies/" + companyId + "/warehouses/" + warehouseId + "/movements/" + view.id()))
			.body(view);
	}

	@GetMapping
	MovementPage list(@PathVariable String companyId, @PathVariable String warehouseId,
			@RequestParam(required = false) @Nullable String page, @RequestParam(required = false) @Nullable String size,
			@RequestParam(required = false) @Nullable String productId,
			@RequestParam(required = false) @Nullable String type) {
		return movements.list(companyId, warehouseId, page, size, productId, type);
	}

	@GetMapping("/{id}")
	MovementView get(@PathVariable String companyId, @PathVariable String warehouseId, @PathVariable String id) {
		return movements.find(companyId, warehouseId, id);
	}

}
