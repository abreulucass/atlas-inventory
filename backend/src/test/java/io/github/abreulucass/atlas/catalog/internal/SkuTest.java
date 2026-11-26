package io.github.abreulucass.atlas.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.abreulucass.atlas.catalog.InvalidSkuException;

class SkuTest {

	@Test
	void stripsAndUpperCases() {
		assertThat(Sku.parse("  ab-12.x_y ")).isEqualTo("AB-12.X_Y");
	}

	@Test
	void acceptsDashDotAndUnderscore() {
		assertThat(Sku.parse("a-b.c_d")).isEqualTo("A-B.C_D");
	}

	@Test
	void acceptsFortyCharactersAndRefusesFortyOne() {
		assertThat(Sku.parse("A".repeat(40))).hasSize(40);
		assertThatThrownBy(() -> Sku.parse("A".repeat(41))).isInstanceOf(InvalidSkuException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "AB/1", "AB 1", "ÁB" })
	void refusesBlankNullAndOtherCharacters(String raw) {
		assertThatThrownBy(() -> Sku.parse(raw)).isInstanceOf(InvalidSkuException.class);
	}

}
