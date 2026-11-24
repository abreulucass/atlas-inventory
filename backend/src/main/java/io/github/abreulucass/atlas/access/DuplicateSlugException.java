package io.github.abreulucass.atlas.access;

public class DuplicateSlugException extends RuntimeException {

	public DuplicateSlugException(String slug) {
		super("Slug already in use: " + slug);
	}

}
