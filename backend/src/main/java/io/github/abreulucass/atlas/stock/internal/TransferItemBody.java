package io.github.abreulucass.atlas.stock.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation only: the controller reads the raw body, so this type never runs. */
record TransferItemBody(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String productId,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Positive") java.math.BigDecimal quantity) {
}
