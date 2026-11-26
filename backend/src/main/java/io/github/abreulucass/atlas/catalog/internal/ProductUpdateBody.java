package io.github.abreulucass.atlas.catalog.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation only: the controller reads the raw body, so this type never runs. sku and unit cannot be changed; send name, categoryId or both. */
record ProductUpdateBody(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Current version, for optimistic locking") long version,
		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String name,
		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Active category id; null clears it", nullable = true) String categoryId) {
}
