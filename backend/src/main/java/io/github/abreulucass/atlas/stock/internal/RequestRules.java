package io.github.abreulucass.atlas.stock.internal;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import io.github.abreulucass.atlas.stock.InvalidMovementException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Body, quantity and note rules shared by movement and transfer requests. */
final class RequestRules {

	// exact decimals: a double would round 1.0001 or 1e9+0.001 before the rules see them
	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.build();

	private static final BigDecimal MAX_QUANTITY = BigDecimal.valueOf(1_000_000_000);

	private static final int MAX_NOTE = 500;

	private RequestRules() {
	}

	/** The body as a JSON object; anything else is a bad request. */
	static JsonNode readBody(@Nullable String body) {
		try {
			JsonNode json = JSON.readTree(body == null ? "" : body);
			if (json.isObject()) {
				return json;
			}
		}
		catch (JacksonException | ArithmeticException | NumberFormatException e) {
			// falls through to the invalid request below
		}
		throw new InvalidMovementException("Body must be a JSON object");
	}

	/** A number, non-zero, at most 1e9 in absolute value, at most 3 decimals; negative only when {@code signed}. */
	static BigDecimal quantity(@Nullable JsonNode node, boolean signed) {
		if (node == null || !node.isNumber()) {
			throw new InvalidMovementException("quantity is required and must be a number");
		}
		BigDecimal quantity;
		try {
			quantity = node.decimalValue().stripTrailingZeros();
		}
		catch (ArithmeticException | NumberFormatException e) {
			throw new InvalidMovementException("quantity must be a representable decimal number");
		}
		if (quantity.signum() == 0 || quantity.abs().compareTo(MAX_QUANTITY) > 0) {
			throw new InvalidMovementException("quantity must be non-zero and at most 1000000000 in absolute value");
		}
		if (quantity.scale() > 3) {
			throw new InvalidMovementException("quantity has at most 3 decimal places");
		}
		if (!signed && quantity.signum() < 0) {
			throw new InvalidMovementException("quantity must be positive");
		}
		return quantity;
	}

	/** Stripped, at most 500 characters, no NUL; blank means absent. */
	static @Nullable String note(@Nullable JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString()) {
			throw new InvalidMovementException("note must be a string");
		}
		String note = node.asString().strip();
		if (note.indexOf('\u0000') >= 0) {
			throw new InvalidMovementException("note must not contain NUL");
		}
		if (note.codePointCount(0, note.length()) > MAX_NOTE) {
			throw new InvalidMovementException("note has at most " + MAX_NOTE + " characters");
		}
		return note.isEmpty() ? null : note;
	}

}
