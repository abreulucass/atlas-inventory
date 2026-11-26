package io.github.abreulucass.atlas.stock;

/** The {@code Idempotency-Key} header is missing or breaks the key format. */
public class InvalidIdempotencyKeyException extends RuntimeException {

	public InvalidIdempotencyKeyException() {
		super("Idempotency-Key is required: 1 to 100 printable ASCII characters");
	}

}
