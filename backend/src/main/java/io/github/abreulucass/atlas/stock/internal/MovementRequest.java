package io.github.abreulucass.atlas.stock.internal;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.github.abreulucass.atlas.stock.InvalidMovementException;
import io.github.abreulucass.atlas.stock.MovementType;
import tools.jackson.databind.JsonNode;

/**
 * A parsed movement body. {@code delta} is the signed effect on the balance; {@code productId} stays the raw string
 * because resolving it is the service's job (unknown and malformed ids answer alike).
 */
public record MovementRequest(MovementType type, String productId, BigDecimal delta, @Nullable String note) {

	public static MovementRequest parse(@Nullable String body) {
		JsonNode json = RequestRules.readBody(body);
		MovementType type = type(json.get("type"));
		JsonNode product = json.get("productId");
		if (product == null || !product.isString()) {
			throw new InvalidMovementException("productId is required and must be a string");
		}
		BigDecimal quantity = RequestRules.quantity(json.get("quantity"), type == MovementType.ADJUSTMENT);
		String note = RequestRules.note(json.get("note"));
		if (type == MovementType.ADJUSTMENT && note == null) {
			throw new InvalidMovementException("note is required for an ADJUSTMENT");
		}
		return new MovementRequest(type, product.asString(), type == MovementType.OUT ? quantity.negate() : quantity,
				note);
	}

	private static MovementType type(@Nullable JsonNode node) {
		if (node != null && node.isString()) {
			// TRANSFER_OUT and TRANSFER_IN only exist inside a transfer
			for (MovementType type : List.of(MovementType.IN, MovementType.OUT, MovementType.ADJUSTMENT)) {
				if (type.name().equals(node.asString())) {
					return type;
				}
			}
		}
		throw new InvalidMovementException("type must be IN, OUT or ADJUSTMENT");
	}

}
