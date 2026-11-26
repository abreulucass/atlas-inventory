package io.github.abreulucass.atlas.catalog;

public class InvalidSkuException extends RuntimeException {

	public InvalidSkuException() {
		super("SKU must have 1 to 40 characters from A-Z, 0-9, -, . and _ after trimming");
	}

}
