package io.github.abreulucass.atlas.access.internal;

class GrantTargetNotFoundException extends RuntimeException {

	GrantTargetNotFoundException() {
		super("Company or warehouse not found");
	}

}
