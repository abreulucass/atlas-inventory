package io.github.abreulucass.atlas.access.internal;

class LastOwnerException extends RuntimeException {

	LastOwnerException() {
		super("The organization must keep at least one OWNER");
	}

}
