package io.github.abreulucass.atlas.warehouse.internal;

import java.util.Locale;
import java.util.regex.Pattern;

import io.github.abreulucass.atlas.warehouse.InvalidCodeException;

public final class WarehouseCode {

	private static final Pattern FORMAT = Pattern.compile("[A-Z0-9-]{1,20}");

	private WarehouseCode() {
	}

	/** Strips and upper-cases; anything outside 1-20 characters of A-Z, 0-9 and - is refused. */
	public static String parse(String raw) {
		String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
		if (!FORMAT.matcher(code).matches()) {
			throw new InvalidCodeException();
		}
		return code;
	}

}
