package io.github.abreulucass.atlas.catalog;

import java.util.UUID;

/** The product changed since the client read it. */
public class StaleProductVersionException extends RuntimeException {

	public StaleProductVersionException(UUID id) {
		super("Product was changed by someone else, reload it: " + id);
	}

}
