package io.github.abreulucass.atlas.catalog;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record ProductView(UUID id, String sku, String name, Unit unit, boolean fractional,
		@Nullable UUID categoryId, Status status, long version) {
}
