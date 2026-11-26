package io.github.abreulucass.atlas.catalog;

/** A product request that is not a JSON object of the expected shape, or breaks a field rule. */
public class InvalidProductException extends RuntimeException {

	public InvalidProductException(String message) {
		super(message);
	}

}
