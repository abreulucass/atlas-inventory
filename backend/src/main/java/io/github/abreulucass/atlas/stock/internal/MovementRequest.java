package io.github.abreulucass.atlas.stock.internal;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import io.github.abreulucass.atlas.stock.InvalidMovementException;
import io.github.abreulucass.atlas.stock.MovementType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A parsed movement body. {@code delta} is the signed effect on the balance; {@code productId} stays the raw string
 * because resolving it is the service's job (unknown and malformed ids answer alike).
 */
public record MovementRequest(MovementType type, String productId, BigDecimal delta, @Nullable String note) {

	// exact decimals: a double would round 1.0001 or 1e9+0.001 before the rules see them
	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.build();

	private static final BigDecimal MAX_QUANTITY = BigDecimal.valueOf(1_000_000_000);

	private static final int MAX_NOTE = 500;

	public static MovementRequest parse(@Nullable String body) {
		JsonNode json = readBody(body);
		MovementType type = type(json.get("type"));
		JsonNode product = json.get("productId");
		if (product == null || !product.isString()) {
			throw new InvalidMovementException("productId is required and must be a string");
		}
		BigDecimal quantity = quantity(json.get("quantity"), type);
		String note = note(json.get("note"));
		if (type == MovementType.ADJUSTMENT && note == null) {
			throw new InvalidMovementException("note is required for an ADJUSTMENT");
		}
		return new MovementRequest(type, product.asString(), type == MovementType.OUT ? quantity.negate() : quantity,
				note);
	}

	private static MovementType type(@Nullable JsonNode node) {
		if (node != null && node.isString()) {
			for (MovementType type : MovementType.values()) {
				if (type.name().equals(node.asString())) {
					return type;
				}
			}
		}
		throw new InvalidMovementException("type must be IN, OUT or ADJUSTMENT");
	}

	private static BigDecimal quantity(@Nullable JsonNode node, MovementType type) {
		if (node == null || !node.isNumber()) {
			throw new InvalidMovementException("quantity is required and must be a number");
		}
		BigDecimal quantity = node.decimalValue().stripTrailingZeros();
		if (quantity.signum() == 0 || quantity.abs().compareTo(MAX_QUANTITY) > 0) {
			throw new InvalidMovementException("quantity must be non-zero and at most 1000000000 in absolute value");
		}
		if (quantity.scale() > 3) {
			throw new InvalidMovementException("quantity has at most 3 decimal places");
		}
		if (type != MovementType.ADJUSTMENT && quantity.signum() < 0) {
			throw new InvalidMovementException("quantity must be positive for IN and OUT");
		}
		return quantity;
	}

	private static @Nullable String note(@Nullable JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString()) {
			throw new InvalidMovementException("note must be a string");
		}
		String note = node.asString().strip();
		if (note.codePointCount(0, note.length()) > MAX_NOTE) {
			throw new InvalidMovementException("note has at most " + MAX_NOTE + " characters");
		}
		return note.isEmpty() ? null : note;
	}

	private static JsonNode readBody(@Nullable String body) {
		try {
			JsonNode json = JSON.readTree(body == null ? "" : body);
			if (json.isObject()) {
				return json;
			}
		}
		catch (JacksonException e) {
			// falls through to the invalid request below
		}
		throw new InvalidMovementException("Body must be a JSON object");
	}

}
