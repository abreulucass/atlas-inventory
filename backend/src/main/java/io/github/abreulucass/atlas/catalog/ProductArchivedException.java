package io.github.abreulucass.atlas.catalog;

import java.util.UUID;

public class ProductArchivedException extends RuntimeException {

	public ProductArchivedException(UUID id) {
		super("Product is archived: " + id);
	}

}
