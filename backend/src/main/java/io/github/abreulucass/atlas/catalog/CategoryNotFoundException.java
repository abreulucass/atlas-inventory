package io.github.abreulucass.atlas.catalog;

/** Also thrown for a category of another organization or an id that is not a UUID: its existence is never revealed. */
public class CategoryNotFoundException extends RuntimeException {

	public CategoryNotFoundException(String id) {
		super("Category not found: " + id);
	}

}
