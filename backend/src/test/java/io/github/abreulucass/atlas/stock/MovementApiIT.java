package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

class MovementApiIT extends StockITSupport {

	String key() {
		return UUID.randomUUID().toString();
	}

	/** Sends the movement and expects the status; the ledger must not move on a refusal. */
	void refused(Scene s, int expected, String json) throws Exception {
		Ledger before = ledger(s.owner().organizationId());
		send(s.owner(), s.path(), key(), json).andExpect(status().is(expected));
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

	@Test
	void inRaisesBalanceAndReturns201ForOwnerAndAdmin() throws Exception {
		Scene s = scene();
		Session admin = joinAs(s.owner(), "ADMIN");

		for (Session as : List.of(s.owner(), admin)) {
			UUID product = createProduct(s.owner(), "IN" + UUID.randomUUID().toString().substring(0, 6), "UN");
			String body = send(as, s.path(), key(), move("IN", product, 10, null)).andExpect(status().isCreated())
				.andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(s.path() + "/[0-9a-f-]{36}")))
				.andReturn()
				.getResponse()
				.getContentAsString();

			assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("10");
			assertThat(number(body, "$.quantity")).isEqualByComparingTo("10");
			assertThat((String) JsonPath.read(body, "$.type")).isEqualTo("IN");
			assertThat((String) JsonPath.read(body, "$.productId")).isEqualTo(product.toString());
			assertThat((String) JsonPath.read(body, "$.warehouseId")).isEqualTo(s.warehouse().toString());
			assertThat((String) JsonPath.read(body, "$.createdBy")).isEqualTo(as.userId().toString());
		}
	}

	@Test
	void outLowersBalanceAndReturnsNegativeQuantity() throws Exception {
		Scene s = scene();
		record(s.owner(), s.path(), move("IN", s.product(), 10, null));

		String body = record(s.owner(), s.path(), move("OUT", s.product(), 4, null));

		assertThat(number(body, "$.quantity")).isEqualByComparingTo("-4");
		assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("6");
		assertThat((String) JsonPath.read(body, "$.type")).isEqualTo("OUT");
	}

	@Test
	void adjustmentAddsSignedDelta() throws Exception {
		Scene s = scene();
		record(s.owner(), s.path(), move("IN", s.product(), 10, null));

		String up = record(s.owner(), s.path(), move("ADJUSTMENT", s.product(), 3, "recount"));
		String down = record(s.owner(), s.path(), move("ADJUSTMENT", s.product(), -2, "breakage"));

		assertThat(number(up, "$.quantity")).isEqualByComparingTo("3");
		assertThat(number(up, "$.balanceAfter")).isEqualByComparingTo("13");
		assertThat(number(down, "$.quantity")).isEqualByComparingTo("-2");
		assertThat(number(down, "$.balanceAfter")).isEqualByComparingTo("11");
		assertThat((String) JsonPath.read(down, "$.note")).isEqualTo("breakage");
	}

	@Test
	void movementBelowZeroReturns409AndChangesNothing() throws Exception {
		Scene s = scene();
		// no balance row yet: an OUT finds balance 0
		refused(s, 409, move("OUT", s.product(), 1, null));
		record(s.owner(), s.path(), move("IN", s.product(), 5, null));

		refused(s, 409, move("OUT", s.product(), 6, null));
		refused(s, 409, move("ADJUSTMENT", s.product(), -6, "too much"));
		UUID kg = createProduct(s.owner(), "KG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "KG");
		record(s.owner(), s.path(), move("IN", kg, 5, null));
		refused(s, 409, move("OUT", kg, "5.001", null));
	}

	@Test
	void memberWithGrantRecordsMovement() throws Exception {
		Scene s = scene();
		Session onWarehouse = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session onCompany = memberWithGrant(s.owner(), "companyId", s.company());

		for (Session member : List.of(onWarehouse, onCompany)) {
			String body = record(member, s.path(), move("IN", s.product(), 2, null));
			assertThat((String) JsonPath.read(body, "$.createdBy")).isEqualTo(member.userId().toString());
		}
		assertThat(ledger(s.owner().organizationId()).movements()).isEqualTo(2);
	}

	@Test
	void memberWithoutGrantReturns404() throws Exception {
		Scene s = scene();
		UUID otherWarehouse = createWarehouse(s.owner(), s.company());
		Session none = joinAs(s.owner(), "MEMBER");
		// a grant to a sibling warehouse says nothing about this one
		Session sibling = memberWithGrant(s.owner(), "warehouseId", otherWarehouse);
		Ledger before = ledger(s.owner().organizationId());

		for (Session member : List.of(none, sibling)) {
			send(member, s.path(), key(), move("IN", s.product(), 1, null)).andExpect(status().isNotFound());
		}
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

	@Test
	void firstInStartsBalanceAtZero() throws Exception {
		Scene s = scene();
		assertThat(ledger(s.owner().organizationId()).balances()).isEmpty();

		String body = record(s.owner(), s.path(), move("IN", s.product(), 5, null));

		assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("5");
		List<java.util.Map<String, Object>> balances = ledger(s.owner().organizationId()).balances();
		assertThat(balances).hasSize(1);
		assertThat((BigDecimal) balances.get(0).get("quantity")).isEqualByComparingTo("5");
		assertThat(balances.get(0)).containsEntry("product_id", s.product()).containsEntry("warehouse_id", s.warehouse());
	}

	@Test
	void movementRecordsAuthorAndServerTime() throws Exception {
		Scene s = scene();
		Session admin = joinAs(s.owner(), "ADMIN");

		Instant start = Instant.now();
		String body = record(admin, s.path(), move("IN", s.product(), 1, null));
		Instant end = Instant.now();

		assertThat((String) JsonPath.read(body, "$.createdBy")).isEqualTo(admin.userId().toString());
		Instant createdAt = Instant.parse(JsonPath.read(body, "$.createdAt"));
		assertThat(createdAt).isBetween(start, end);
	}

	@Test
	void invalidTypeReturns400() throws Exception {
		Scene s = scene();

		refused(s, 400, "{\"productId\": \"%s\", \"quantity\": 1}".formatted(s.product()));
		refused(s, 400, move("TRANSFER", s.product(), 1, null));
		refused(s, 400, move("in", s.product(), 1, null));
		refused(s, 400, "{\"type\": null, \"productId\": \"%s\", \"quantity\": 1}".formatted(s.product()));
	}

	@Test
	void invalidQuantityReturns400() throws Exception {
		Scene s = scene();

		refused(s, 400, "{\"type\": \"IN\", \"productId\": \"%s\"}".formatted(s.product()));
		refused(s, 400, move("IN", s.product(), "\"5\"", null));
		refused(s, 400, move("IN", s.product(), 0, null));
		refused(s, 400, move("ADJUSTMENT", s.product(), 0, "zero"));
		refused(s, 400, move("IN", s.product(), "1000000000.001", null));
		refused(s, 400, move("ADJUSTMENT", s.product(), "-1000000000.001", "big"));
		String body = record(s.owner(), s.path(), move("IN", s.product(), 1000000000, null));
		assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("1000000000");
	}

	@Test
	void negativeInOrOutReturns400() throws Exception {
		Scene s = scene();

		refused(s, 400, move("IN", s.product(), -1, null));
		refused(s, 400, move("OUT", s.product(), -1, null));
	}

	@Test
	void moreThanThreeDecimalsReturns400() throws Exception {
		Scene s = scene();
		UUID kg = createProduct(s.owner(), "KG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "KG");

		refused(s, 400, move("IN", kg, "1.0001", null));
		String body = record(s.owner(), s.path(), move("IN", kg, "1.001", null));
		assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("1.001");
	}

	@Test
	void fractionalQuantityOnWholeUnitReturns400() throws Exception {
		Scene s = scene();
		record(s.owner(), s.path(), move("IN", s.product(), 3, null));

		refused(s, 400, move("IN", s.product(), "1.5", null));
		refused(s, 400, move("ADJUSTMENT", s.product(), "-0.5", "half"));
		for (String unit : List.of("CX", "PC")) {
			UUID whole = createProduct(s.owner(), unit + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), unit);
			refused(s, 400, move("IN", whole, "0.5", null));
		}
	}

	@Test
	void adjustmentWithoutNoteReturns400() throws Exception {
		Scene s = scene();

		refused(s, 400, move("ADJUSTMENT", s.product(), 1, null));
		refused(s, 400, move("ADJUSTMENT", s.product(), 1, "   "));
		refused(s, 400, "{\"type\": \"ADJUSTMENT\", \"productId\": \"%s\", \"quantity\": 1, \"note\": null}"
			.formatted(s.product()));
	}

	@Test
	void noteLongerThan500Returns400() throws Exception {
		Scene s = scene();

		refused(s, 400, move("IN", s.product(), 1, "a".repeat(501)));
		refused(s, 400, move("IN", s.product(), 1, "  " + "a".repeat(501)));
		String body = record(s.owner(), s.path(), move("IN", s.product(), 1, "a".repeat(500)));
		assertThat((String) JsonPath.read(body, "$.note")).hasSize(500);
	}

	@Test
	void invalidProductIdReturns400Or404() throws Exception {
		Scene s = scene();
		Session other = register();
		UUID foreign = createProduct(other, "FOREIGN", "UN");

		refused(s, 400, "{\"type\": \"IN\", \"quantity\": 1}");
		refused(s, 404, move("IN", UUID.randomUUID(), 1, null));
		refused(s, 404, move("IN", foreign, 1, null));
		refused(s, 404, move("IN", "not-a-uuid", 1, null));
		refused(s, 404, move("IN", "1-1-1-1-1", 1, null));
	}

	@Test
	void archivedResourceReturns409() throws Exception {
		Scene s = scene();
		UUID archivedProduct = seedProduct(s.owner().organizationId(), "ARCH", "UN", true);
		refused(s, 409, move("IN", archivedProduct, 1, null));

		UUID warehouse = createWarehouse(s.owner(), s.company());
		archive(s.owner(), "/api/companies/" + s.company() + "/warehouses/" + warehouse);
		Scene inArchivedWarehouse = new Scene(s.owner(), s.company(), warehouse, s.product());
		refused(inArchivedWarehouse, 409, move("IN", s.product(), 1, null));

		UUID company = createCompany(s.owner());
		UUID companyWarehouse = createWarehouse(s.owner(), company);
		archive(s.owner(), "/api/companies/" + company);
		Scene inArchivedCompany = new Scene(s.owner(), company, companyWarehouse, s.product());
		refused(inArchivedCompany, 409, move("IN", s.product(), 1, null));
	}

	@Test
	void archivedOrganizationReturns409() throws Exception {
		Scene s = scene();
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session admin = joinAs(s.owner(), "ADMIN");
		archiveOrganization(s.owner().organizationId());
		Ledger before = ledger(s.owner().organizationId());

		for (Session as : List.of(s.owner(), admin, member)) {
			send(as, s.path(), key(), move("IN", s.product(), 1, null)).andExpect(status().isConflict());
		}
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

	@Test
	void invalidPathReturns404() throws Exception {
		Scene s = scene();
		Session other = register();
		UUID otherCompany = createCompany(other);
		UUID otherWarehouse = createWarehouse(other, otherCompany);
		UUID siblingCompany = createCompany(s.owner());
		String json = move("IN", s.product(), 1, null);
		Ledger before = ledger(s.owner().organizationId());

		List<String> paths = List.of(
				"/api/companies/" + otherCompany + "/warehouses/" + otherWarehouse + "/movements",
				"/api/companies/" + otherCompany + "/warehouses/" + s.warehouse() + "/movements",
				"/api/companies/" + s.company() + "/warehouses/" + otherWarehouse + "/movements",
				"/api/companies/" + UUID.randomUUID() + "/warehouses/" + s.warehouse() + "/movements",
				"/api/companies/" + s.company() + "/warehouses/" + UUID.randomUUID() + "/movements",
				"/api/companies/not-a-uuid/warehouses/" + s.warehouse() + "/movements",
				"/api/companies/" + s.company() + "/warehouses/not-a-uuid/movements",
				// the warehouse exists, but not in this company
				"/api/companies/" + siblingCompany + "/warehouses/" + s.warehouse() + "/movements");
		for (String path : paths) {
			send(s.owner(), path, key(), json).andExpect(status().isNotFound());
		}
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
		assertThat(ledger(other.organizationId()).movements()).isZero();
	}

	@Test
	void invalidIdempotencyKeyReturns400() throws Exception {
		Scene s = scene();
		String json = move("IN", s.product(), 1, null);
		Ledger before = ledger(s.owner().organizationId());

		send(s.owner(), s.path(), null, json).andExpect(status().isBadRequest());
		for (String key : List.of("", "k".repeat(101), "has space", "chá", "tab\tkey")) {
			send(s.owner(), s.path(), key, json).andExpect(status().isBadRequest());
		}
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
		send(s.owner(), s.path(), "k".repeat(100), json).andExpect(status().isCreated());
	}

	@Test
	void adjustmentToZeroIsAccepted() throws Exception {
		Scene s = scene();
		record(s.owner(), s.path(), move("IN", s.product(), 4, null));

		String body = record(s.owner(), s.path(), move("ADJUSTMENT", s.product(), -4, "write-off"));

		assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("0");
		assertThat((BigDecimal) ledger(s.owner().organizationId()).balances().get(0).get("quantity"))
			.isEqualByComparingTo("0");
	}

	@Test
	void trailingZerosCountAsWhole() throws Exception {
		Scene s = scene();

		String body = record(s.owner(), s.path(), move("IN", s.product(), "10.000", null));

		assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("10");
	}

}
