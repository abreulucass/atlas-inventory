package io.github.abreulucass.atlas.stock;

/** A movement body that is not a JSON object of the expected shape, or breaks a field rule. */
public class InvalidMovementException extends RuntimeException {

	public InvalidMovementException(String message) {
		super(message);
	}

}
