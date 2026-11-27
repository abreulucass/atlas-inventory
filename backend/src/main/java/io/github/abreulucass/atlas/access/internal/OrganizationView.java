package io.github.abreulucass.atlas.access.internal;

import java.time.Instant;
import java.util.UUID;

import io.github.abreulucass.atlas.access.Status;

record OrganizationView(UUID id, String name, String slug, Role role, Status status) {

	// used by the constructor-expression query in MembershipRepository
	OrganizationView(UUID id, String name, String slug, Role role, Instant archivedAt) {
		this(id, name, slug, role, archivedAt == null ? Status.ACTIVE : Status.ARCHIVED);
	}

}
