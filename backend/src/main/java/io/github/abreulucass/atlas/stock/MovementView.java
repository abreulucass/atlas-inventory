package io.github.abreulucass.atlas.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * {@code quantity} is the signed effect on the balance (an OUT is negative); {@code transferId} is set on the two
 * halves of a transfer and null otherwise.
 */
public record MovementView(UUID id, UUID warehouseId, UUID productId, MovementType type, BigDecimal quantity,
		BigDecimal balanceAfter, @Nullable String note, UUID createdBy, Instant createdAt,
		@Nullable UUID transferId) {
}
