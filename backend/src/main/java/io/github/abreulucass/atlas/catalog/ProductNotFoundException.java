package io.github.abreulucass.atlas.catalog;

/** Also thrown for a product of another organization or an id that is not a UUID: its existence is never revealed. */
public class ProductNotFoundException extends RuntimeException {

	public ProductNotFoundException(String id) {
		super("Product not found: " + id);
	}

}
