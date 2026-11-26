package io.github.abreulucass.atlas.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** A recorded transfer; {@code items} keep the request order and quantities have scale 3, as stored. */
public record TransferView(UUID id, UUID fromWarehouseId, UUID toWarehouseId, @Nullable String note,
		List<Item> items, UUID createdBy, Instant createdAt) {

	public record Item(UUID productId, BigDecimal quantity) {
	}

}
