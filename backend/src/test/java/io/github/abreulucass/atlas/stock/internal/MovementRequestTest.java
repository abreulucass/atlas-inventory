package io.github.abreulucass.atlas.stock.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.abreulucass.atlas.stock.InvalidMovementException;
import io.github.abreulucass.atlas.stock.MovementType;

class MovementRequestTest {

	static String body(String type, String quantity, String note) {
		return "{\"type\":" + type + ",\"productId\":\"p\",\"quantity\":" + quantity
				+ (note == null ? "" : ",\"note\":" + note) + "}";
	}

	static void refuses(String body) {
		assertThatThrownBy(() -> MovementRequest.parse(body)).isInstanceOf(InvalidMovementException.class);
	}

	@Test
	void inKeepsPositiveQuantityAndOutNegatesIt() {
		MovementRequest in = MovementRequest.parse(body("\"IN\"", "10", null));
		MovementRequest out = MovementRequest.parse(body("\"OUT\"", "4", null));

		assertThat(in.type()).isEqualTo(MovementType.IN);
		assertThat(in.productId()).isEqualTo("p");
		assertThat(in.delta()).isEqualByComparingTo("10");
		assertThat(out.type()).isEqualTo(MovementType.OUT);
		assertThat(out.delta()).isEqualByComparingTo("-4");
	}

	@Test
	void adjustmentTakesSignedNonZeroQuantityWithNote() {
		assertThat(MovementRequest.parse(body("\"ADJUSTMENT\"", "3", "\"count\"")).delta()).isEqualByComparingTo("3");
		assertThat(MovementRequest.parse(body("\"ADJUSTMENT\"", "-2", "\"count\"")).delta()).isEqualByComparingTo("-2");
		refuses(body("\"ADJUSTMENT\"", "0", "\"count\""));
	}

	@Test
	void transferTypesAreRefused() {
		refuses(body("\"TRANSFER_OUT\"", "1", null));
		refuses(body("\"TRANSFER_IN\"", "1", null));
	}

	@Test
	void negativeQuantityIsRefusedOnInAndOut() {
		refuses(body("\"IN\"", "-1", null));
		refuses(body("\"OUT\"", "-1", null));
	}

	@Test
	void zeroIsRefusedForEveryType() {
		refuses(body("\"IN\"", "0", null));
		refuses(body("\"OUT\"", "0.000", null));
	}

	@Test
	void quantityUpToOneBillionIsAcceptedAndOneMillionthMoreIsNot() {
		assertThat(MovementRequest.parse(body("\"IN\"", "1000000000", null)).delta()).isEqualByComparingTo("1000000000");
		assertThat(MovementRequest.parse(body("\"IN\"", "1000000000.000", null)).delta())
			.isEqualByComparingTo("1000000000");
		refuses(body("\"IN\"", "1000000000.001", null));
		refuses(body("\"ADJUSTMENT\"", "-1000000000.001", "\"x\""));
		refuses(body("\"IN\"", "1e400", null));
		// the decimal conversion or normalization itself overflows
		refuses(body("\"IN\"", "100e2147483647", null));
		refuses(body("\"ADJUSTMENT\"", "-100e2147483647", "\"x\""));
		refuses(body("\"IN\"", "1e-2147483648", null));
	}

	@Test
	void threeDecimalsAreAcceptedAndFourAreNot() {
		assertThat(MovementRequest.parse(body("\"IN\"", "1.001", null)).delta()).isEqualByComparingTo("1.001");
		refuses(body("\"IN\"", "1.0001", null));
		refuses(body("\"IN\"", "0.0001", null));
	}

	@Test
	void trailingZerosDoNotCountAsDecimals() {
		MovementRequest request = MovementRequest.parse(body("\"IN\"", "10.000", null));

		assertThat(request.delta()).isEqualByComparingTo("10");
		assertThat(request.delta().scale()).isLessThanOrEqualTo(0);
		assertThat(MovementRequest.parse(body("\"IN\"", "1.5000", null)).delta()).isEqualByComparingTo("1.5");
	}

	@Test
	void doubleRoundingDoesNotLetFourDecimalsThrough() {
		refuses(body("\"IN\"", "1.00000000000000001", null));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "\"IN \"", "\"in\"", "\"TRANSFER\"", "1", "true", "null" })
	void missingOrUnknownTypeIsRefused(String type) {
		refuses(type == null ? "{\"productId\":\"p\",\"quantity\":1}"
				: "{\"type\":" + type + ",\"productId\":\"p\",\"quantity\":1}");
	}

	@Test
	void missingOrNonStringProductIdIsRefused() {
		refuses("{\"type\":\"IN\",\"quantity\":1}");
		refuses("{\"type\":\"IN\",\"productId\":null,\"quantity\":1}");
		refuses("{\"type\":\"IN\",\"productId\":7,\"quantity\":1}");
	}

	@Test
	void missingOrNonNumberQuantityIsRefused() {
		refuses("{\"type\":\"IN\",\"productId\":\"p\"}");
		refuses(body("\"IN\"", "null", null));
		refuses(body("\"IN\"", "\"5\"", null));
		refuses(body("\"IN\"", "true", null));
		refuses(body("\"IN\"", "[5]", null));
	}

	@Test
	void bodyThatIsNotAnObjectIsRefused() {
		refuses(null);
		refuses("");
		refuses("not json");
		refuses("[]");
		refuses("5");
	}

	@Test
	void noteIsStrippedAndBlankMeansAbsent() {
		assertThat(MovementRequest.parse(body("\"IN\"", "1", "\"  restock \"")).note()).isEqualTo("restock");
		assertThat(MovementRequest.parse(body("\"IN\"", "1", "\"   \"")).note()).isNull();
		assertThat(MovementRequest.parse(body("\"IN\"", "1", "null")).note()).isNull();
		assertThat(MovementRequest.parse(body("\"IN\"", "1", null)).note()).isNull();
	}

	@Test
	void noteUpToFiveHundredCharactersIsAcceptedAndMoreIsNot() {
		assertThat(MovementRequest.parse(body("\"IN\"", "1", "\"" + "a".repeat(500) + "\"")).note()).hasSize(500);
		// 500 code points, 1000 chars: the limit counts characters the user sees
		assertThat(MovementRequest.parse(body("\"IN\"", "1", "\"" + "😀".repeat(500) + "\"")).note()).isNotNull();
		refuses(body("\"IN\"", "1", "\"" + "a".repeat(501) + "\""));
		refuses(body("\"IN\"", "1", "\"  " + "a".repeat(501) + "\""));
		assertThat(MovementRequest.parse(body("\"IN\"", "1", "\"  " + "a".repeat(500) + "  \"")).note()).hasSize(500);
	}

	@Test
	void nonStringNoteIsRefused() {
		refuses(body("\"IN\"", "1", "5"));
		refuses(body("\"IN\"", "1", "\"a\\u0000b\""));
	}

	@Test
	void adjustmentWithoutOrBlankNoteIsRefused() {
		refuses(body("\"ADJUSTMENT\"", "1", null));
		refuses(body("\"ADJUSTMENT\"", "1", "\"   \""));
		refuses(body("\"ADJUSTMENT\"", "1", "null"));
	}

	@Test
	void quantityKeepsExactValue() {
		assertThat(MovementRequest.parse(body("\"IN\"", "123456789.123", null)).delta())
			.isEqualTo(new BigDecimal("123456789.123"));
	}

}
