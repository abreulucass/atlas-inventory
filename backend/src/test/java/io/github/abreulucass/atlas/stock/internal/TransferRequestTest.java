package io.github.abreulucass.atlas.stock.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.abreulucass.atlas.stock.InvalidMovementException;

class TransferRequestTest {

	static final String W1 = "11111111-1111-1111-1111-111111111111";

	static final String W2 = "22222222-2222-2222-2222-222222222222";

	static String body(String from, String to, String items, String note) {
		return "{" + (from == null ? "" : "\"fromWarehouseId\":\"" + from + "\",")
				+ (to == null ? "" : "\"toWarehouseId\":\"" + to + "\",")
				+ (items == null ? "" : "\"items\":" + items + ",") + (note == null ? "" : "\"note\":" + note + ",")
				+ "\"x\":1}";
	}

	static String oneItem(String quantity) {
		return "[{\"productId\":\"p\",\"quantity\":" + quantity + "}]";
	}

	static void refuses(String body) {
		assertThatThrownBy(() -> TransferRequest.parse(body)).isInstanceOf(InvalidMovementException.class);
	}

	@Test
	void parsesWarehousesItemsInOrderAndNote() {
		TransferRequest request = TransferRequest.parse(body(W1, W2,
				"[{\"productId\":\"b\",\"quantity\":2},{\"productId\":\"a\",\"quantity\":1.5}]", "\"  moved \""));

		assertThat(request.fromWarehouseId()).isEqualTo(W1);
		assertThat(request.toWarehouseId()).isEqualTo(W2);
		assertThat(request.note()).isEqualTo("moved");
		assertThat(request.items()).extracting(TransferRequest.Item::productId).containsExactly("b", "a");
		assertThat(request.items().get(1).quantity()).isEqualByComparingTo("1.5");
	}

	@Test
	void missingWarehouseIdsAreRefused() {
		refuses(body(null, W2, oneItem("1"), null));
		refuses(body(W1, null, oneItem("1"), null));
		refuses("{\"fromWarehouseId\":1,\"toWarehouseId\":\"" + W2 + "\",\"items\":" + oneItem("1") + "}");
	}

	@Test
	void sameWarehouseIsRefusedEvenWithDifferentCase() {
		refuses(body(W1, W1, oneItem("1"), null));
		refuses(body("AAAAAAAA-1111-1111-1111-111111111111", "aaaaaaaa-1111-1111-1111-111111111111", oneItem("1"),
				null));
		refuses(body("not-a-uuid", "not-a-uuid", oneItem("1"), null));
	}

	@Test
	void itemsMustHoldOneToHundredEntries() {
		refuses(body(W1, W2, null, null));
		refuses(body(W1, W2, "[]", null));
		refuses(body(W1, W2, "{}", null));
		refuses(body(W1, W2, "[1]", null));
		assertThat(TransferRequest.parse(body(W1, W2, items(100), null)).items()).hasSize(100);
		refuses(body(W1, W2, items(101), null));
	}

	static String items(int count) {
		return IntStream.range(0, count)
			.mapToObj(i -> "{\"productId\":\"p" + i + "\",\"quantity\":1}")
			.collect(Collectors.joining(",", "[", "]"));
	}

	@Test
	void productIdIsRequiredAndUnique() {
		refuses(body(W1, W2, "[{\"quantity\":1}]", null));
		refuses(body(W1, W2, "[{\"productId\":5,\"quantity\":1}]", null));
		refuses(body(W1, W2, "[{\"productId\":\"p\",\"quantity\":1},{\"productId\":\"p\",\"quantity\":2}]", null));
		refuses(body(W1, W2, "[{\"productId\":\"AAAAAAAA-1111-1111-1111-111111111111\",\"quantity\":1},"
				+ "{\"productId\":\"aaaaaaaa-1111-1111-1111-111111111111\",\"quantity\":1}]", null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.000", "-1", "1000000000.001", "1.0001", "\"3\"", "null", "true" })
	void invalidQuantitiesAreRefused(String quantity) {
		refuses(body(W1, W2, oneItem(quantity), null));
	}

	@Test
	void missingQuantityIsRefused() {
		refuses(body(W1, W2, "[{\"productId\":\"p\"}]", null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "1000000000", "0.001", "1.234", "10.000" })
	void validQuantitiesAreAccepted(String quantity) {
		assertThat(TransferRequest.parse(body(W1, W2, oneItem(quantity), null)).items().get(0).quantity())
			.isEqualByComparingTo(quantity);
	}

	@Test
	void trailingZerosAreStripped() {
		assertThat(TransferRequest.parse(body(W1, W2, oneItem("10.000"), null)).items().get(0).quantity().scale())
			.isLessThanOrEqualTo(0);
	}

	@Test
	void noteIsStrippedBlankMeansAbsentAndBoundsApply() {
		assertThat(TransferRequest.parse(body(W1, W2, oneItem("1"), "\"   \"")).note()).isNull();
		assertThat(TransferRequest.parse(body(W1, W2, oneItem("1"), "null")).note()).isNull();
		assertThat(TransferRequest.parse(body(W1, W2, oneItem("1"), "\"" + "x".repeat(500) + "\"")).note())
			.hasSize(500);
		assertThat(TransferRequest.parse(body(W1, W2, oneItem("1"), "\" " + "x".repeat(500) + " \"")).note())
			.hasSize(500);
		refuses(body(W1, W2, oneItem("1"), "\"" + "x".repeat(501) + "\""));
		refuses(body(W1, W2, oneItem("1"), "\"a\\u0000b\""));
		refuses(body(W1, W2, oneItem("1"), "5"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "[]", "\"x\"", "not json", "null" })
	void bodyMustBeAJsonObject(String body) {
		refuses(body);
		assertThatThrownBy(() -> TransferRequest.parse(null)).isInstanceOf(InvalidMovementException.class);
	}

}
