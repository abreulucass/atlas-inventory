package io.github.abreulucass.atlas.stock.internal;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.stock.TransferView;
import io.github.abreulucass.atlas.stock.Transfers;

@RestController
@RequestMapping("/api/transfers")
class TransferController {

	private final Transfers transfers;

	TransferController(Transfers transfers) {
		this.transfers = transfers;
	}

	@PostMapping
	ResponseEntity<TransferView> record(@RequestHeader(name = "Idempotency-Key", required = false) @Nullable String key,
			@RequestBody(required = false) @Nullable String body) {
		TransferView view = transfers.record(key, body);
		return ResponseEntity.created(URI.create("/api/transfers/" + view.id())).body(view);
	}

	@GetMapping("/{id}")
	TransferView find(@PathVariable String id) {
		return transfers.find(id);
	}

}
