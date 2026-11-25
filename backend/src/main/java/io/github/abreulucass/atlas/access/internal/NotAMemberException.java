package io.github.abreulucass.atlas.access.internal;

class NotAMemberException extends RuntimeException {

	NotAMemberException() {
		super("Not a member of this organization");
	}

}
