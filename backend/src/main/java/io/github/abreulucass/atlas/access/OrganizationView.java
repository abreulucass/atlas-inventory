package io.github.abreulucass.atlas.access;

import java.util.UUID;

public record OrganizationView(UUID id, String name, String slug, Status status) {
}
