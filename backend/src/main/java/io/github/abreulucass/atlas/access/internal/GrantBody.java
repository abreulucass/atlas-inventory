package io.github.abreulucass.atlas.access.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation only: the controller reads the raw body, so this type never runs. Send exactly one of companyId or warehouseId. */
record GrantBody(
		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Grants access to a whole company") String companyId,
		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Grants access to one warehouse") String warehouseId) {
}
