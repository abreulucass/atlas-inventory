package io.github.abreulucass.atlas.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.abreulucass.atlas.company.InvalidCnpjException;

class CnpjTest {

	static void assertRejected(List<String> values) {
		for (String value : values) {
			assertThatThrownBy(() -> Cnpj.parse(value)).as(value).isInstanceOf(InvalidCnpjException.class);
		}
	}

	@Test
	void stripsMaskAndUppercases() {
		assertThat(Cnpj.parse("12.abc.345/01de-35")).isEqualTo("12ABC34501DE35");
		assertThat(Cnpj.parse("11.222.333/0001-81")).isEqualTo("11222333000181");
	}

	@Test
	void acceptsValidNumericCnpj() {
		assertThat(Cnpj.parse("11222333000181")).isEqualTo("11222333000181");
		assertThat(Cnpj.parse("00000000000191")).isEqualTo("00000000000191");
	}

	@Test
	void acceptsValidAlphanumericCnpj() {
		// example published by the Receita Federal for the alphanumeric CNPJ
		assertThat(Cnpj.parse("12ABC34501DE35")).isEqualTo("12ABC34501DE35");
	}

	@Test
	void rejectsWrongCheckDigit() {
		assertRejected(List.of("11222333000182", "11222333000171", "12ABC34501DE36", "12ABC34501DE45",
				"11.222.333/0001-80"));
	}

	@Test
	void rejectsWrongLength() {
		assertRejected(List.of("", "1122233300018", "112223330001811", "11.222.333/0001-8", "12ABC34501DE3"));
	}

	@Test
	void rejectsAllCharactersEqual() {
		// 00000000000000 has valid check digits, so only the all-equal rule refuses it
		assertRejected(List.of("00000000000000", "00.000.000/0000-00", "11111111111111", "99999999999999",
				"AAAAAAAAAAAAAA"));
	}

	@Test
	void rejectsLettersInCheckDigits() {
		assertRejected(List.of("12ABC34501DE3A", "12ABC34501DEA5", "1122233300018B"));
	}

	@Test
	void rejectsPunctuationOutsideFullMask() {
		assertRejected(List.of("11222333000181-", "11222333/000181", "112.22.333/0001-81", "11.222.333.0001-81",
				"11-222-333-0001-81", ".11222333000181", "11.222.333/0001-81 "));
	}

}
