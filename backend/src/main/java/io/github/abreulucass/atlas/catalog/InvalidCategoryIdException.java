package io.github.abreulucass.atlas.catalog;

/** A {@code categoryId} in a request body that is not a canonical UUID. */
public class InvalidCategoryIdException extends RuntimeException {

	public InvalidCategoryIdException() {
		super("categoryId must be a UUID");
	}

}
