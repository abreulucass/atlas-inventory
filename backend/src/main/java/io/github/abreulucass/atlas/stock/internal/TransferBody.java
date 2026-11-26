package io.github.abreulucass.atlas.stock.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation only: the controller reads the raw body, so this type never runs. */
record TransferBody(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fromWarehouseId,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Must differ from fromWarehouseId") String toWarehouseId,
		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String note,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 100 items, distinct products") java.util.List<TransferItemBody> items) {
}
