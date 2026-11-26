package io.github.abreulucass.atlas.stock;

/** A movement that is not in the path's warehouse, or a path id that is not a UUID; never reveals other tenants' rows. */
public class StockNotFoundException extends RuntimeException {

	public StockNotFoundException(String message) {
		super(message);
	}

}
