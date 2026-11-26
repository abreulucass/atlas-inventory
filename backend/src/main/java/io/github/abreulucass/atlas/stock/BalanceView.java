package io.github.abreulucass.atlas.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceView(UUID productId, String sku, String name, String unit, BigDecimal quantity, Instant updatedAt) {
}
