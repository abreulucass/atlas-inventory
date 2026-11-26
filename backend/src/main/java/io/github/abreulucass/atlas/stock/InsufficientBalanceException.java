package io.github.abreulucass.atlas.stock;

/** An OUT or negative ADJUSTMENT would leave the balance below zero. */
public class InsufficientBalanceException extends RuntimeException {

	public InsufficientBalanceException() {
		super("Insufficient balance for this movement");
	}

}
