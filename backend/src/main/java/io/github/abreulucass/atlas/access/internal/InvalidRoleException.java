package io.github.abreulucass.atlas.access.internal;

class InvalidRoleException extends RuntimeException {

	InvalidRoleException() {
		super("role must be OWNER, ADMIN or MEMBER");
	}

}
