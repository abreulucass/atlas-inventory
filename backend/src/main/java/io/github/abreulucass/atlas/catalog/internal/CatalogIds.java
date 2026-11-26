package io.github.abreulucass.atlas.catalog.internal;

import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/** Only the canonical 8-4-4-4-12 spelling: {@link UUID#fromString} also takes short forms like {@code 1-1-1-1-1}. */
public final class CatalogIds {

	private static final Pattern CANONICAL = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private CatalogIds() {
	}

	/** The id, or null when the text is not a canonical UUID. */
	public static @Nullable UUID parse(@Nullable String text) {
		return text != null && CANONICAL.matcher(text).matches() ? UUID.fromString(text) : null;
	}

}
