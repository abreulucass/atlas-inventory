package io.github.abreulucass.atlas.warehouse;

import java.util.UUID;

public record WarehouseView(UUID id, UUID companyId, String name, String code, Status status) {
}
