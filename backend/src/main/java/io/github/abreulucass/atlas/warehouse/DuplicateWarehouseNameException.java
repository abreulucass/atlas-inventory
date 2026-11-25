package io.github.abreulucass.atlas.warehouse;

public class DuplicateWarehouseNameException extends RuntimeException {

	public DuplicateWarehouseNameException(String name) {
		super("Warehouse name already in use in this company: " + name);
	}

}
