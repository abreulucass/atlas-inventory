package io.github.abreulucass.atlas.catalog;

public class DuplicateSkuException extends RuntimeException {

	public DuplicateSkuException(String sku) {
		super("SKU already in use in this organization: " + sku);
	}

}
