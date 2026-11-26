package io.github.abreulucass.atlas.stock;

/** The idempotency key was already used by a different request in this organization. */
public class IdempotencyKeyReusedException extends RuntimeException {

	public IdempotencyKeyReusedException() {
		super("Idempotency-Key was already used with a different request");
	}

}
