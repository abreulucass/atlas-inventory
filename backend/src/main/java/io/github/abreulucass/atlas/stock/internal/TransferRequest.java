package io.github.abreulucass.atlas.stock.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import io.github.abreulucass.atlas.stock.InvalidMovementException;
import tools.jackson.databind.JsonNode;

/**
 * A parsed transfer body. Warehouse and product ids stay raw strings because resolving them is the service's job
 * (unknown and malformed ids answer alike); quantities are positive and stripped of trailing zeros.
 */
public record TransferRequest(String fromWarehouseId, String toWarehouseId, @Nullable String note, List<Item> items) {

	public static final int MAX_ITEMS = 100;

	public record Item(String productId, BigDecimal quantity) {
	}

	public static TransferRequest parse(@Nullable String body) {
		JsonNode json = RequestRules.readBody(body);
		String from = text(json.get("fromWarehouseId"), "fromWarehouseId");
		String to = text(json.get("toWarehouseId"), "toWarehouseId");
		if (sameId(from, to)) {
			throw new InvalidMovementException("fromWarehouseId and toWarehouseId must differ");
		}
		JsonNode array = json.get("items");
		if (array == null || !array.isArray() || array.isEmpty() || array.size() > MAX_ITEMS) {
			throw new InvalidMovementException("items is required and must hold 1 to " + MAX_ITEMS + " entries");
		}
		List<Item> items = new ArrayList<>();
		Set<Object> seen = new HashSet<>();
		for (JsonNode node : array) {
			if (!node.isObject()) {
				throw new InvalidMovementException("each item must be an object");
			}
			String product = text(node.get("productId"), "productId");
			if (!seen.add(key(product))) {
				throw new InvalidMovementException("productId " + product + " appears more than once");
			}
			items.add(new Item(product, RequestRules.quantity(node.get("quantity"), false)));
		}
		return new TransferRequest(from, to, RequestRules.note(json.get("note")), List.copyOf(items));
	}

	private static String text(@Nullable JsonNode node, String field) {
		if (node == null || !node.isString()) {
			throw new InvalidMovementException(field + " is required and must be a string");
		}
		return node.asString();
	}

	private static boolean sameId(String a, String b) {
		return key(a).equals(key(b));
	}

	/** The parsed UUID when the text is one (so letter case does not matter), else the text itself. */
	private static Object key(String id) {
		UUID parsed = StockIds.parse(id);
		return parsed == null ? id : parsed;
	}

}
