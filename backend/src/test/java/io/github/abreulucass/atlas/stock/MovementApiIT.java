package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

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

	/** Two faults at once: the answer is the earlier rule's, told apart by status and ProblemDetail, ledger unchanged. */
	void earlier(Scene s, String path, String key, String json, int expected, String detail, String notDetail)
			throws Exception {
		Ledger before = ledger(s.owner().organizationId());
		send(s.owner(), path, key, json).andExpect(status().is(expected))
			.andExpect(jsonPath("$.detail").value(containsString(detail)))
			.andExpect(jsonPath("$.detail").value(not(containsString(notDetail))));
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

	@Test
	void earlierFaultWinsWhenRulesConflict() throws Exception {
		Scene s = scene();
		UUID archivedProduct = seedProduct(s.owner().organizationId(), "ARCHP", "UN", true);
		UUID unknown = UUID.randomUUID();
		String badPath = "/api/companies/" + UUID.randomUUID() + "/warehouses/" + s.warehouse() + "/movements";
		String badBody = "{\"type\": \"IN\"}";
		String badKey = "has space";
		// 404 path before 400 key and body, and before 404 product
		earlier(s, badPath, badKey, badBody, 404, "Company not found", "Idempotency");
		earlier(s, badPath, key(), move("IN", unknown, 1, null), 404, "Company not found", "Product");
		// 400 key before 400 body
		earlier(s, s.path(), badKey, badBody, 400, "Idempotency-Key", "productId");
		earlier(s, s.path(), null, badBody, 400, "Idempotency-Key", "productId");
		// 400 body before 404 product (a malformed quantity beside an unknown product)
		earlier(s, s.path(), key(), move("IN", unknown, 0, null), 400, "quantity", "Product");
		// 404 product before 400 whole quantity
		earlier(s, s.path(), key(), move("IN", unknown, "1.5", null), 404, "Product not found", "whole number");
		// 400 whole quantity before 409 archived and before 409 insufficient
		earlier(s, s.path(), key(), move("OUT", archivedProduct, "1.5", null), 400, "whole number", "archived");
		earlier(s, s.path(), key(), move("OUT", s.product(), "1.5", null), 400, "whole number", "Insufficient");
		// 409 archived before 409 insufficient (no balance exists, so every OUT would also be insufficient)
		earlier(s, s.path(), key(), move("OUT", archivedProduct, 1, null), 409, "archived", "Insufficient");
		UUID archivedWarehouse = createWarehouse(s.owner(), s.company());
		archive(s.owner(), "/api/companies/" + s.company() + "/warehouses/" + archivedWarehouse);
		Scene inArchivedWarehouse = new Scene(s.owner(), s.company(), archivedWarehouse, s.product());
		earlier(inArchivedWarehouse, inArchivedWarehouse.path(), key(), move("OUT", s.product(), 1, null), 409,
				"warehouse is archived", "Insufficient");
		// among the archived resources the company comes first, then the warehouse, then the product
		earlier(inArchivedWarehouse, inArchivedWarehouse.path(), key(), move("IN", archivedProduct, 1, null), 409,
				"warehouse is archived", "product");
		UUID company = createCompany(s.owner());
		UUID companyWarehouse = createWarehouse(s.owner(), company);
		archive(s.owner(), "/api/companies/" + company + "/warehouses/" + companyWarehouse);
		archive(s.owner(), "/api/companies/" + company);
		Scene inArchivedCompany = new Scene(s.owner(), company, companyWarehouse, s.product());
		earlier(inArchivedCompany, inArchivedCompany.path(), key(), move("IN", archivedProduct, 1, null), 409,
				"company is archived", "warehouse");
		// 404 path before 409 archived company
		earlier(inArchivedCompany, "/api/companies/" + company + "/warehouses/" + s.warehouse() + "/movements", key(),
				move("IN", s.product(), 1, null), 404, "not found", "archived");
	}

	@Test
	void unauthenticatedAndArchivedOrganizationAnswerFirst() throws Exception {
		Scene s = scene();
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		archiveOrganization(s.owner().organizationId());
		String badPath = "/api/companies/" + UUID.randomUUID() + "/warehouses/" + s.warehouse() + "/movements";
		Ledger before = ledger(s.owner().organizationId());

		// 401 before 409 archived organization, whatever else is wrong
		for (String bearer : List.of("", "Bearer garbage")) {
			var request = post(badPath).contentType(MediaType.APPLICATION_JSON).content("{\"type\": \"IN\"}");
			if (!bearer.isEmpty()) {
				request.header("Authorization", bearer);
			}
			mvc.perform(request.header("Idempotency-Key", "has space")).andExpect(status().isUnauthorized());
		}
		// 409 archived organization before 404 path, 400 key, 400 body, 404 product and 409 archived resource
		for (Session as : List.of(s.owner(), member)) {
			for (String[] fault : List.of(new String[] { badPath, key(), move("IN", s.product(), 1, null) },
					new String[] { s.path(), "has space", move("IN", s.product(), 1, null) },
					new String[] { s.path(), key(), "{\"type\": \"IN\"}" },
					new String[] { s.path(), key(), move("IN", UUID.randomUUID(), 1, null) },
					new String[] { s.path(), key(), move("IN", s.product(), "1.5", null) })) {
				send(as, fault[0], fault[1], fault[2]).andExpect(status().isConflict())
					.andExpect(jsonPath("$.detail").value(containsString("Organization is archived")));
			}
		}
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

	@Test
	void inRaisesBalanceAndReturns201ForOwnerAndAdmin() throws Exception {
		Scene s = scene();
		Session admin = joinAs(s.owner(), "ADMIN");

		for (Session as : List.of(s.owner(), admin)) {
			UUID product = createProduct(s.owner(), "IN" + UUID.randomUUID().toString().substring(0, 6), "UN");
			var result = send(as, s.path(), key(), move("IN", product, 10, null)).andExpect(status().isCreated())
				.andReturn()
				.getResponse();
			String body = result.getContentAsString();
			assertThat(result.getHeader("Location")).isEqualTo(s.path() + "/" + JsonPath.read(body, "$.id"));
			assertPersisted(s, product, body, "IN", "10", "10", as.userId(), null);
			assertThat(balanceOf(s.owner().organizationId(), s.warehouse(), product)).isEqualByComparingTo("10");

			assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("10");
			assertThat(number(body, "$.quantity")).isEqualByComparingTo("10");
			assertThat((String) JsonPath.read(body, "$.type")).isEqualTo("IN");
			assertThat((String) JsonPath.read(body, "$.productId")).isEqualTo(product.toString());
			assertThat((String) JsonPath.read(body, "$.warehouseId")).isEqualTo(s.warehouse().toString());
			assertThat((String) JsonPath.read(body, "$.createdBy")).isEqualTo(as.userId().toString());
			assertThat(JsonPath.<Object>read(body, "$.transferId")).isNull();
		}
	}

	@Test
	void outLowersBalanceAndReturnsNegativeQuantity() throws Exception {
		Scene s = scene();
		String in = record(s.owner(), s.path(), move("IN", s.product(), 10, null));

		String body = record(s.owner(), s.path(), move("OUT", s.product(), 4, null));

		assertThat(number(body, "$.quantity")).isEqualByComparingTo("-4");
		assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("6");
		assertThat((String) JsonPath.read(body, "$.type")).isEqualTo("OUT");
		assertThat(JsonPath.<Object>read(body, "$.transferId")).isNull();
		assertPersisted(s, s.product(), body, "OUT", "-4", "6", s.owner().userId(), null);
		assertThat(ledger(s.owner().organizationId()).entries()).extracting(e -> e.get("id").toString())
			.containsExactly((String) JsonPath.read(in, "$.id"), JsonPath.read(body, "$.id"));
		assertThat(balanceOf(s.owner().organizationId(), s.warehouse(), s.product())).isEqualByComparingTo("6");
	}

	@Test
	void adjustmentAddsSignedDelta() throws Exception {
		Scene s = scene();
		String in = record(s.owner(), s.path(), move("IN", s.product(), 10, null));

		String up = record(s.owner(), s.path(), move("ADJUSTMENT", s.product(), 3, "recount"));
		String down = record(s.owner(), s.path(), move("ADJUSTMENT", s.product(), -2, "breakage"));

		assertThat(number(up, "$.quantity")).isEqualByComparingTo("3");
		assertThat(number(up, "$.balanceAfter")).isEqualByComparingTo("13");
		assertThat(number(down, "$.quantity")).isEqualByComparingTo("-2");
		assertThat(number(down, "$.balanceAfter")).isEqualByComparingTo("11");
		assertThat((String) JsonPath.read(down, "$.note")).isEqualTo("breakage");
		assertThat(JsonPath.<Object>read(up, "$.transferId")).isNull();
		assertThat(JsonPath.<Object>read(down, "$.transferId")).isNull();
		assertPersisted(s, s.product(), up, "ADJUSTMENT", "3", "13", s.owner().userId(), "recount");
		assertPersisted(s, s.product(), down, "ADJUSTMENT", "-2", "11", s.owner().userId(), "breakage");
		assertThat(ledger(s.owner().organizationId()).entries()).extracting(e -> e.get("id").toString())
			.containsExactly((String) JsonPath.read(in, "$.id"), JsonPath.read(up, "$.id"), JsonPath.read(down, "$.id"));
		assertThat(balanceOf(s.owner().organizationId(), s.warehouse(), s.product())).isEqualByComparingTo("11");
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
		refused(s, 400, move("IN", s.product(), "100e2147483647", null));
		refused(s, 400, move("ADJUSTMENT", s.product(), "-100e2147483647", "huge"));
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
		// an escaped NUL cannot be stored in PostgreSQL text
		refused(s, 400, move("IN", s.product(), 1, "a\\u0000b"));
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
		assertPersisted(s, s.product(), body, "ADJUSTMENT", "-4", "0", s.owner().userId(), "write-off");
		assertThat(ledger(s.owner().organizationId()).balances()).hasSize(1);
		assertThat((BigDecimal) ledger(s.owner().organizationId()).balances().get(0).get("quantity"))
			.isEqualByComparingTo("0");
		assertThat(ledger(s.owner().organizationId()).movements()).isEqualTo(2);
	}

	@Test
	void trailingZerosCountAsWhole() throws Exception {
		Scene s = scene();

		String body = record(s.owner(), s.path(), move("IN", s.product(), "10.000", null));

		assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("10");
	}

}
