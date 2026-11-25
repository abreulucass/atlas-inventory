package io.github.abreulucass.atlas.access.internal;

class UserNotFoundException extends RuntimeException {

	UserNotFoundException() {
		super("No registered user has this e-mail");
	}

}
