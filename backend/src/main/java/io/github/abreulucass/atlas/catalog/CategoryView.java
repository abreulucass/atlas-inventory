package io.github.abreulucass.atlas.catalog;

import java.util.UUID;

public record CategoryView(UUID id, String name, Status status) {
}
