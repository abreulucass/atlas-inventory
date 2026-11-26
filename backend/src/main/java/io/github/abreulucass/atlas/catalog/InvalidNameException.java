package io.github.abreulucass.atlas.catalog;

public class InvalidNameException extends RuntimeException {

	public InvalidNameException(int max) {
		super("Name must have 1 to " + max + " characters after trimming");
	}

}
