package io.github.abreulucass.atlas.access.internal;

import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/** Only the canonical 8-4-4-4-12 spelling: {@link UUID#fromString} also takes short forms like {@code 1-1-1-1-1}. */
final class Uuids {

	private static final Pattern CANONICAL = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private Uuids() {
	}

	/** The id, or null when the text is not a canonical UUID. */
	static @Nullable UUID parse(String text) {
		return CANONICAL.matcher(text).matches() ? UUID.fromString(text) : null;
	}

}
