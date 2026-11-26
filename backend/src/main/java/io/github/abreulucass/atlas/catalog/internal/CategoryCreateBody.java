package io.github.abreulucass.atlas.catalog.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation only: the controller reads the raw body, so this type never runs. */
record CategoryCreateBody(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {
}
