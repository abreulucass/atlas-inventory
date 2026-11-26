package io.github.abreulucass.atlas.catalog.internal;

import java.util.Locale;
import java.util.regex.Pattern;

import io.github.abreulucass.atlas.catalog.InvalidSkuException;

public final class Sku {

	private static final Pattern FORMAT = Pattern.compile("[A-Z0-9._-]{1,40}");

	private Sku() {
	}

	/** Strips and upper-cases; anything outside 1-40 characters of A-Z, 0-9, -, . and _ is refused. */
	public static String parse(String raw) {
		String sku = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
		if (!FORMAT.matcher(sku).matches()) {
			throw new InvalidSkuException();
		}
		return sku;
	}

}
