package io.github.abreulucass.atlas.access.internal;

import java.util.UUID;

public record MemberView(UUID userId, String name, String email, Role role) {
}
