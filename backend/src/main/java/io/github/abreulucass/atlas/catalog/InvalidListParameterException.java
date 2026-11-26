package io.github.abreulucass.atlas.catalog;

/** A product list parameter outside its allowed values. */
public class InvalidListParameterException extends RuntimeException {

	public InvalidListParameterException(String message) {
		super(message);
	}

}
