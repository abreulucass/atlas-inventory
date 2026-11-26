package io.github.abreulucass.atlas.catalog;

/** Fixed units of measure; the fractional ones can be counted in parts. */
public enum Unit {

	UN(false), CX(false), PC(false), KG(true), G(true), L(true), ML(true), M(true);

	private final boolean fractional;

	Unit(boolean fractional) {
		this.fractional = fractional;
	}

	public boolean fractional() {
		return fractional;
	}

	/** The exact upper-case name; anything else, null included, is refused. */
	public static Unit parse(String raw) {
		for (Unit unit : values()) {
			if (unit.name().equals(raw)) {
				return unit;
			}
		}
		throw new InvalidUnitException();
	}

}
