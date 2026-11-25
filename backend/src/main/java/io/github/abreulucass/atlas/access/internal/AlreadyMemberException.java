package io.github.abreulucass.atlas.access.internal;

class AlreadyMemberException extends RuntimeException {

	AlreadyMemberException() {
		super("The user is already a member of this organization");
	}

}
