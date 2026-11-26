package io.github.abreulucass.atlas.stock.internal;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.github.abreulucass.atlas.stock.InvalidIdempotencyKeyException;

public final class IdempotencyKey {

	private static final Pattern FORMAT = Pattern.compile("[\\x21-\\x7E]{1,100}");

	private IdempotencyKey() {
	}

	/** The key as sent: 1-100 printable ASCII characters (0x21-0x7E), nothing stripped. */
	public static String parse(@Nullable String raw) {
		if (raw == null || !FORMAT.matcher(raw).matches()) {
			throw new InvalidIdempotencyKeyException();
		}
		return raw;
	}

}
