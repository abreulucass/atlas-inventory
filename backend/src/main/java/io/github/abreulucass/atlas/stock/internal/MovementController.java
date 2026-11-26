package io.github.abreulucass.atlas.stock.internal;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.stock.MovementView;
import io.github.abreulucass.atlas.stock.Movements;

@RestController
@RequestMapping("/api/companies/{companyId}/warehouses/{warehouseId}/movements")
class MovementController {

	private final Movements movements;

	MovementController(Movements movements) {
		this.movements = movements;
	}

	@PostMapping
	ResponseEntity<MovementView> record(@PathVariable String companyId, @PathVariable String warehouseId,
			@RequestHeader(name = "Idempotency-Key", required = false) @Nullable String key,
			@RequestBody(required = false) @Nullable String body) {
		MovementView view = movements.record(companyId, warehouseId, key, body);
		return ResponseEntity
			.created(URI.create("/api/companies/" + companyId + "/warehouses/" + warehouseId + "/movements/" + view.id()))
			.body(view);
	}

}
