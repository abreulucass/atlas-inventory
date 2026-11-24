package io.github.abreulucass.atlas.access;

public class InvalidSlugException extends RuntimeException {

	public InvalidSlugException(String slug) {
		super("Invalid slug (3 to 63 chars of a-z, 0-9 and single hyphens inside): " + slug);
	}

}
