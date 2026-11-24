package io.github.abreulucass.atlas.access;

import java.util.UUID;

public class OrganizationNotFoundException extends RuntimeException {

	public OrganizationNotFoundException(UUID id) {
		super("Organization not found: " + id);
	}

}
