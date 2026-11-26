package io.github.abreulucass.atlas.catalog;

public class InvalidUnitException extends RuntimeException {

	public InvalidUnitException() {
		super("Unit must be one of UN, CX, PC, KG, G, L, ML, M");
	}

}
