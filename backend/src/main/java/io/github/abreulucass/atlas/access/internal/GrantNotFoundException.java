package io.github.abreulucass.atlas.access.internal;

class GrantNotFoundException extends RuntimeException {

	GrantNotFoundException() {
		super("Grant not found");
	}

}
