package io.github.abreulucass.atlas.company;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record CompanyView(UUID id, String name, @Nullable String cnpj, Status status) {
}
