package io.github.abreulucass.atlas.warehouse.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.abreulucass.atlas.warehouse.InvalidCodeException;

class WarehouseCodeTest {

	@Test
	void stripsAndUpperCases() {
		assertThat(WarehouseCode.parse("  cd-sp1 ")).isEqualTo("CD-SP1");
	}

	@Test
	void acceptsTwentyCharacters() {
		assertThat(WarehouseCode.parse("a".repeat(20))).isEqualTo("A".repeat(20));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "CD_SP", "CD SP", "CÓD", "ç" })
	void rejectsBlankAndForeignCharacters(String raw) {
		assertThatThrownBy(() -> WarehouseCode.parse(raw)).isInstanceOf(InvalidCodeException.class);
	}

	@Test
	void rejectsTwentyOneCharacters() {
		assertThatThrownBy(() -> WarehouseCode.parse("A".repeat(21))).isInstanceOf(InvalidCodeException.class);
	}

	@Test
	void rejectsNull() {
		assertThatThrownBy(() -> WarehouseCode.parse(null)).isInstanceOf(InvalidCodeException.class);
	}

}
