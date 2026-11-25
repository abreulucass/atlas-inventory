package io.github.abreulucass.atlas.warehouse;

public class DuplicateWarehouseCodeException extends RuntimeException {

	public DuplicateWarehouseCodeException(String code) {
		super("Warehouse code already in use in this company: " + code);
	}

}
