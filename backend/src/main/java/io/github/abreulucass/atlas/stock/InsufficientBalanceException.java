package io.github.abreulucass.atlas.stock;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** An OUT, negative ADJUSTMENT or transfer item would leave the balance below zero. */
public class InsufficientBalanceException extends RuntimeException {

	private final @Nullable UUID productId;

	public InsufficientBalanceException() {
		this(null);
	}

	/** {@code productId} names the failing transfer item; null for a single movement. */
	public InsufficientBalanceException(@Nullable UUID productId) {
		super(productId == null ? "Insufficient balance for this movement"
				: "Insufficient balance for product " + productId);
		this.productId = productId;
	}

	public @Nullable UUID productId() {
		return productId;
	}

}
