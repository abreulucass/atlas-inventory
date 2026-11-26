package io.github.abreulucass.atlas.access.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

record GrantView(GrantType type, UUID companyId, @Nullable UUID warehouseId) {
}
