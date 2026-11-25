package io.github.abreulucass.atlas.warehouse;

import java.util.UUID;

/** Also thrown for a warehouse of another company or organization: its existence is never revealed. */
public class WarehouseNotFoundException extends RuntimeException {

	public WarehouseNotFoundException(UUID id) {
		super("Warehouse not found: " + id);
	}

}
