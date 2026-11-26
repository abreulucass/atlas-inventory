package io.github.abreulucass.atlas.catalog;

import java.util.UUID;

public class CategoryArchivedException extends RuntimeException {

	public CategoryArchivedException(UUID id) {
		super("Category is archived: " + id);
	}

}
