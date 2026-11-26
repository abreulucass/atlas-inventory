package io.github.abreulucass.atlas.stock;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code total} sums only {@code warehouses}, the ones the caller sees. */
public record ProductStock(UUID productId, BigDecimal total, List<WarehouseStock> warehouses) {

	public record WarehouseStock(UUID companyId, UUID warehouseId, BigDecimal quantity) {
	}

}
