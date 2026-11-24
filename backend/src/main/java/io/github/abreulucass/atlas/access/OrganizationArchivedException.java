package io.github.abreulucass.atlas.access;

import java.util.UUID;

public class OrganizationArchivedException extends RuntimeException {

	public OrganizationArchivedException(UUID id) {
		super("Organization is archived: " + id);
	}

}
