package io.github.abreulucass.atlas.warehouse;

public class InvalidCodeException extends RuntimeException {

	public InvalidCodeException() {
		super("Code must have 1 to 20 characters from A-Z, 0-9 and - after trimming");
	}

}
