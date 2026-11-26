package io.github.abreulucass.atlas.catalog.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation only: the controller reads the raw body, so this type never runs. */
record ProductCreateBody(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Unique per organization, 1-64 chars, stored upper-case") String sku,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"UN", "CX", "PC", "KG", "G", "L", "ML", "M"}) String unit,
		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Active category id; null for none", nullable = true) String categoryId) {
}
