package io.github.abreulucass.atlas.access;

public class MissingTenantContextException extends RuntimeException {

	public MissingTenantContextException() {
		super("No tenant context is bound to the current execution");
	}

}
