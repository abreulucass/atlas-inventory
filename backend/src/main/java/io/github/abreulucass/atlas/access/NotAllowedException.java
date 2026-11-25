package io.github.abreulucass.atlas.access;

/** The current user's role does not allow the operation. */
public class NotAllowedException extends RuntimeException {

	public NotAllowedException() {
		super("Your role in this organization does not allow this operation");
	}

}
