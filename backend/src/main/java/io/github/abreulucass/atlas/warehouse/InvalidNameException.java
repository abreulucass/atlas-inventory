package io.github.abreulucass.atlas.warehouse;

public class InvalidNameException extends RuntimeException {

	public InvalidNameException() {
		super("Name must have 1 to 120 characters after trimming");
	}

}
