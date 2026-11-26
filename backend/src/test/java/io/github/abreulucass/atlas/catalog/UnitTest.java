package io.github.abreulucass.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class UnitTest {

	@ParameterizedTest
	@EnumSource(value = Unit.class, names = { "KG", "G", "L", "ML", "M" })
	void measuredUnitsAreFractional(Unit unit) {
		assertThat(unit.fractional()).isTrue();
	}

	@ParameterizedTest
	@EnumSource(value = Unit.class, names = { "UN", "CX", "PC" })
	void countedUnitsAreWhole(Unit unit) {
		assertThat(unit.fractional()).isFalse();
	}

	@Test
	void parsesEveryUnitByName() {
		for (Unit unit : Unit.values()) {
			assertThat(Unit.parse(unit.name())).isSameAs(unit);
		}
		assertThat(Unit.values()).hasSize(8);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "XX", "", "kg", " KG" })
	void refusesNullAndUnknown(String raw) {
		assertThatThrownBy(() -> Unit.parse(raw)).isInstanceOf(InvalidUnitException.class);
	}

}
