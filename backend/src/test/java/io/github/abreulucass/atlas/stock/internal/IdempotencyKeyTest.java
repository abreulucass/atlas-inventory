package io.github.abreulucass.atlas.stock.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.abreulucass.atlas.stock.InvalidIdempotencyKeyException;

class IdempotencyKeyTest {

	@Test
	void acceptsOneAndHundredCharactersAndRefusesHundredOne() {
		assertThat(IdempotencyKey.parse("k")).isEqualTo("k");
		assertThat(IdempotencyKey.parse("k".repeat(100))).hasSize(100);
		assertThatThrownBy(() -> IdempotencyKey.parse("k".repeat(101))).isInstanceOf(InvalidIdempotencyKeyException.class);
	}

	@Test
	void acceptsEveryPrintableAsciiExceptSpace() {
		assertThat(IdempotencyKey.parse("!")).isEqualTo("!");
		assertThat(IdempotencyKey.parse("~")).isEqualTo("~");
		assertThat(IdempotencyKey.parse("3f2b-ORDER_9/a")).isEqualTo("3f2b-ORDER_9/a");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "a b", " a", "a ", "a\u007Fb", "a\tb", "a\nb", "chá", "\u00A0" })
	void refusesNullEmptySpaceControlAndNonAscii(String raw) {
		assertThatThrownBy(() -> IdempotencyKey.parse(raw)).isInstanceOf(InvalidIdempotencyKeyException.class);
	}

}
