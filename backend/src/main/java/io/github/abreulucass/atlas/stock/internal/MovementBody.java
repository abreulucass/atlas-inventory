package io.github.abreulucass.atlas.stock.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation only: the controller reads the raw body, so this type never runs. */
record MovementBody(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"IN", "OUT", "ADJUSTMENT"}) String type,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String productId,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Positive for IN and OUT; signed non-zero for ADJUSTMENT") java.math.BigDecimal quantity,
		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Required for ADJUSTMENT") String note) {
}
