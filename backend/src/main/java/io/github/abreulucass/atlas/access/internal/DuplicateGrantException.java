package io.github.abreulucass.atlas.access.internal;

class DuplicateGrantException extends RuntimeException {

	DuplicateGrantException() {
		super("Grant already exists");
	}

}
