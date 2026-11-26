package io.github.abreulucass.atlas.catalog;

public class DuplicateCategoryNameException extends RuntimeException {

	public DuplicateCategoryNameException(String name) {
		super("Category name already in use in this organization: " + name);
	}

}
