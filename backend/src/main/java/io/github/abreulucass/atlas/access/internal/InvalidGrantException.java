package io.github.abreulucass.atlas.access.internal;

class InvalidGrantException extends RuntimeException {

	InvalidGrantException() {
		super("Send exactly one of companyId and warehouseId, as a UUID");
	}

}
