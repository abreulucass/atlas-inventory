package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

class TransferApiIT extends TransferITSupport {

	/** Sends as every writer and expects the status with the whole persisted state unchanged. */
	void refused(TScene s, int expected, String json) throws Exception {
		for (Session as : writers(s)) {
			Snapshot before = snapshot(s.tenant());
			transfer(as, key(), json).andExpect(status().is(expected));
			assertThat(snapshot(s.tenant())).as("state after %d for %s".formatted(expected, as.userId()))
				.isEqualTo(before);
		}
	}

	/** Two faults at once: the earlier rule answers, told apart by status and detail, state unchanged. */
	void earlier(TScene s, Session as, String key, String json, int expected, String detail, String notDetail)
			throws Exception {
		Snapshot before = snapshot(s.tenant());
		transfer(as, key, json).andExpect(status().is(expected))
			.andExpect(jsonPath("$.detail").value(containsString(detail)))
			.andExpect(jsonPath("$.detail").value(not(containsString(notDetail))));
		assertThat(snapshot(s.tenant())).isEqualTo(before);
	}

	Map<String, Object> balanceRow(TScene s, UUID warehouse, UUID product) {
		return snapshot(s.tenant()).ledger()
			.balances()
			.stream()
			.filter(b -> b.get("warehouse_id").equals(warehouse) && b.get("product_id").equals(product))
			.findFirst()
			.orElse(null);
	}

	BigDecimal quantityIn(TScene s, UUID warehouse, UUID product) {
		Map<String, Object> row = balanceRow(s, warehouse, product);
		return row == null ? null : (BigDecimal) row.get("quantity");
	}

	@Test
	void transferMovesEveryItemForOwnerAndAdmin() throws Exception {
		TScene s = tscene();
		stock(s, s.w1(), s.p1(), 10);
		stock(s, s.w1(), s.p2(), 5);
		Session admin = joinAs(s.owner(), "ADMIN");
		BigDecimal p1 = BigDecimal.valueOf(20);
		BigDecimal p2 = BigDecimal.TEN;
		int round = 0;

		for (Session as : List.of(s.owner(), admin)) {
			var response = transfer(as, key(), transferBody(s.w1(), s.w2(), null, s.p1(), 4, s.p2(), 5))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse();
			String body = response.getContentAsString();
			round++;

			assertThat(response.getHeader("Location")).isEqualTo("/api/transfers/" + JsonPath.read(body, "$.id"));
			assertThat((String) JsonPath.read(body, "$.fromWarehouseId")).isEqualTo(s.w1().toString());
			assertThat((String) JsonPath.read(body, "$.toWarehouseId")).isEqualTo(s.w2().toString());
			assertThat((String) JsonPath.read(body, "$.items[0].productId")).isEqualTo(s.p1().toString());
			assertThat(number(body, "$.items[0].quantity")).isEqualByComparingTo("4");
			assertThat((String) JsonPath.read(body, "$.items[1].productId")).isEqualTo(s.p2().toString());
			assertThat(number(body, "$.items[1].quantity")).isEqualByComparingTo("5");
			assertThat(quantityIn(s, s.w1(), s.p1())).isEqualByComparingTo(p1.subtract(BigDecimal.valueOf(4L * round)));
			assertThat(quantityIn(s, s.w1(), s.p2())).isEqualByComparingTo(p2.subtract(BigDecimal.valueOf(5L * round)));
			assertThat(quantityIn(s, s.w2(), s.p1())).isEqualByComparingTo(BigDecimal.valueOf(4L * round));
			assertThat(quantityIn(s, s.w2(), s.p2())).isEqualByComparingTo(BigDecimal.valueOf(5L * round));
			assertThat(snapshot(s.tenant()).transfers()).hasSize(round);
		}
	}

	@Test
	void transferRecordsOutAndInMovementsPerItem() throws Exception {
		TScene s = tscene();
		Snapshot before = snapshot(s.tenant());

		String body = transferOk(s.owner(), transferBody(s.w1(), s.w2(), "restock", s.p1(), 4, s.p2(), 5));

		UUID transfer = UUID.fromString(JsonPath.read(body, "$.id"));
		List<Map<String, Object>> created = snapshot(s.tenant()).ledger()
			.entries()
			.stream()
			.filter(e -> transfer.equals(e.get("transfer_id")))
			.toList();
		assertThat(created).hasSize(4);
		assertThat(snapshot(s.tenant()).ledger().entries()).hasSize(before.ledger().entries().size() + 4);
		for (var item : List.of(Map.entry(s.p1(), 4), Map.entry(s.p2(), 5))) {
			UUID product = item.getKey();
			int q = item.getValue();
			Map<String, Object> out = created.stream()
				.filter(e -> e.get("product_id").equals(product) && e.get("type").equals("TRANSFER_OUT"))
				.findFirst()
				.orElseThrow();
			Map<String, Object> in = created.stream()
				.filter(e -> e.get("product_id").equals(product) && e.get("type").equals("TRANSFER_IN"))
				.findFirst()
				.orElseThrow();
			BigDecimal originStart = product.equals(s.p1()) ? BigDecimal.TEN : BigDecimal.valueOf(5);
			assertThat(out).containsEntry("warehouse_id", s.w1())
				.containsEntry("company_id", s.companyA())
				.containsEntry("tenant_id", s.tenant())
				.containsEntry("note", "restock")
				.containsEntry("created_by", s.owner().userId())
				.containsEntry("idempotency_key", null);
			assertThat((BigDecimal) out.get("quantity")).isEqualByComparingTo(BigDecimal.valueOf(-q));
			assertThat((BigDecimal) out.get("balance_after")).isEqualByComparingTo(originStart.subtract(BigDecimal.valueOf(q)));
			assertThat(in).containsEntry("warehouse_id", s.w2())
				.containsEntry("note", "restock")
				.containsEntry("created_by", s.owner().userId())
				.containsEntry("idempotency_key", null);
			assertThat((BigDecimal) in.get("quantity")).isEqualByComparingTo(BigDecimal.valueOf(q));
			assertThat((BigDecimal) in.get("balance_after")).isEqualByComparingTo(BigDecimal.valueOf(q));
		}
	}

	@Test
	void transferAcrossCompaniesIsRecorded() throws Exception {
		TScene s = tscene();

		String body = transferOk(s.owner(), transferBody(s.w1(), s.w3(), null, s.p1(), 3));

		assertThat((String) JsonPath.read(body, "$.toWarehouseId")).isEqualTo(s.w3().toString());
		assertThat(quantityIn(s, s.w3(), s.p1())).isEqualByComparingTo("3");
		assertThat(balanceRow(s, s.w3(), s.p1())).containsEntry("company_id", s.companyB());
		assertThat(quantityIn(s, s.w1(), s.p1())).isEqualByComparingTo("7");
		Map<String, Object> row = snapshot(s.tenant()).transfers().get(0);
		assertThat(row).containsEntry("from_company_id", s.companyA()).containsEntry("to_company_id", s.companyB());
	}

	@Test
	void firstEntryInDestinationStartsAtZero() throws Exception {
		TScene s = tscene();
		assertThat(balanceRow(s, s.w2(), s.p1())).isNull();

		transferOk(s.owner(), transferBody(s.w1(), s.w2(), null, s.p1(), 4));

		assertThat(quantityIn(s, s.w2(), s.p1())).isEqualByComparingTo("4");
		assertThat(balanceRow(s, s.w2(), s.p1())).containsEntry("sku", balanceRow(s, s.w1(), s.p1()).get("sku"));
		// a second transfer adds to the row the first one created
		transferOk(s.owner(), transferBody(s.w1(), s.w2(), null, s.p1(), 1));
		assertThat(quantityIn(s, s.w2(), s.p1())).isEqualByComparingTo("5");
	}

	@Test
	void memberSeeingBothWarehousesTransfers() throws Exception {
		TScene s = tscene();
		Session byWarehouses = memberWith(s, "warehouseId", s.w1(), s.w2());
		Session byCompany = memberWith(s, "companyId", s.companyA());
		Session mixed = memberWith(s, "warehouseId", s.w1());
		mvc.perform(post("/api/members/" + mixed.userId() + "/access").header("Authorization", s.owner().bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"companyId\": \"%s\"}".formatted(s.companyB())))
			.andExpect(status().isCreated());

		for (Session member : List.of(byWarehouses, byCompany)) {
			transferOk(member, transferBody(s.w1(), s.w2(), null, s.p1(), 1));
			transferOk(member, transferBody(s.w2(), s.w1(), null, s.p1(), 1));
		}
		// across companies, the second grant being a company grant
		transferOk(mixed, transferBody(s.w1(), s.w3(), null, s.p1(), 1));

		assertThat(snapshot(s.tenant()).transfers()).hasSize(5);
		assertThat(snapshot(s.tenant()).transfers()).allSatisfy(t -> assertThat(t.get("created_by"))
			.isIn(byWarehouses.userId(), byCompany.userId(), mixed.userId()));
	}

	@Test
	void memberMissingEitherWarehouseGets404() throws Exception {
		TScene s = tscene();
		Session noDestination = memberWith(s, "warehouseId", s.w1());
		Session noOrigin = memberWith(s, "warehouseId", s.w2());
		Session neither = memberWith(s, "warehouseId", s.w3());
		Session noGrant = joinAs(s.owner(), "MEMBER");

		for (Session member : List.of(noDestination, noOrigin, neither, noGrant)) {
			Snapshot before = snapshot(s.tenant());
			transfer(member, key(), transferBody(s.w1(), s.w2(), null, s.p1(), 1)).andExpect(status().isNotFound());
			assertThat(snapshot(s.tenant())).isEqualTo(before);
		}
	}

	@Test
	void transferRecordsCreatorAndServerTime() throws Exception {
		TScene s = tscene();
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWith(s, "warehouseId", s.w1(), s.w2());

		for (Session as : List.of(s.owner(), admin, member)) {
			Instant before = Instant.now().minusSeconds(1);
			String body = transferOk(as, transferBody(s.w1(), s.w2(), null, s.p1(), 1));
			Instant after = Instant.now().plusSeconds(1);

			Instant createdAt = Instant.parse(JsonPath.read(body, "$.createdAt"));
			assertThat((String) JsonPath.read(body, "$.createdBy")).isEqualTo(as.userId().toString());
			assertThat(createdAt).isBetween(before, after);
			UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
			Map<String, Object> row = snapshot(s.tenant()).transfers()
				.stream()
				.filter(t -> t.get("id").equals(id))
				.findFirst()
				.orElseThrow();
			assertThat(row).containsEntry("created_by", as.userId());
			assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(createdAt);
		}
	}

	@Test
	void insufficientItemFailsWholeTransferNamingProduct() throws Exception {
		TScene s = tscene();
		UUID p3 = createProduct(s.owner(), sku(), "UN");

		for (Session as : writers(s)) {
			Snapshot before = snapshot(s.tenant());
			// p1 fits, p2 (balance 5) and p3 (no balance) do not: the first failing item in request order is named
			transfer(as, key(), transferBody(s.w1(), s.w2(), null, s.p1(), 4, s.p2(), 6, p3, 1))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.productId").value(s.p2().toString()));
			transfer(as, key(), transferBody(s.w1(), s.w2(), null, p3, 1, s.p1(), 4, s.p2(), 6))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.productId").value(p3.toString()));
			assertThat(snapshot(s.tenant())).isEqualTo(before);
		}
	}

	@Test
	void archivedItemProductFailsWholeTransfer() throws Exception {
		TScene s = tscene();
		archive(s.owner(), "/api/products/" + s.p2());

		refused(s, 409, transferBody(s.w1(), s.w2(), null, s.p1(), 1, s.p2(), 1));
		refused(s, 409, transferBody(s.w1(), s.w2(), null, s.p2(), 1, s.p1(), 1));
	}

	@Test
	void failedTransferLeavesNoDestinationRow() throws Exception {
		TScene s = tscene();
		UUID p3 = createProduct(s.owner(), sku(), "UN");

		refused(s, 409, transferBody(s.w1(), s.w2(), null, s.p1(), 1, s.p2(), 1, p3, 1));

		assertThat(snapshot(s.tenant()).ledger().balances()).noneMatch(b -> b.get("warehouse_id").equals(s.w2()));
	}

	@Test
	void missingWarehouseIdIs400() throws Exception {
		TScene s = tscene();
		String items = "\"items\": [{\"productId\": \"%s\", \"quantity\": 1}]".formatted(s.p1());

		refused(s, 400, "{\"toWarehouseId\": \"%s\", %s}".formatted(s.w2(), items));
		refused(s, 400, "{\"fromWarehouseId\": \"%s\", %s}".formatted(s.w1(), items));
		refused(s, 400, "{\"fromWarehouseId\": null, \"toWarehouseId\": \"%s\", %s}".formatted(s.w2(), items));
	}

	@Test
	void sameWarehouseIs400() throws Exception {
		TScene s = tscene();

		refused(s, 400, transferBody(s.w1(), s.w1(), null, s.p1(), 1));
		refused(s, 400, transferBody(s.w1(), s.w1().toString().toUpperCase(), null, s.p1(), 1));
	}

	@Test
	void itemsMissingEmptyOrOver100Is400() throws Exception {
		TScene s = tscene();
		String warehouses = "\"fromWarehouseId\": \"%s\", \"toWarehouseId\": \"%s\"".formatted(s.w1(), s.w2());
		List<String> hundredAndOne = new ArrayList<>();
		for (int i = 0; i < 101; i++) {
			hundredAndOne.add("{\"productId\": \"%s\", \"quantity\": 1}".formatted(UUID.randomUUID()));
		}

		refused(s, 400, "{" + warehouses + "}");
		refused(s, 400, "{" + warehouses + ", \"items\": []}");
		refused(s, 400, "{" + warehouses + ", \"items\": [" + String.join(",", hundredAndOne) + "]}");
	}

	@Test
	void missingOrDuplicateProductIs400() throws Exception {
		TScene s = tscene();
		String warehouses = "\"fromWarehouseId\": \"%s\", \"toWarehouseId\": \"%s\"".formatted(s.w1(), s.w2());

		refused(s, 400, "{" + warehouses + ", \"items\": [{\"quantity\": 1}]}");
		refused(s, 400, transferBody(s.w1(), s.w2(), null, s.p1(), 1, s.p1(), 2));
		refused(s, 400, transferBody(s.w1(), s.w2(), null, s.p1(), 1, s.p1().toString().toUpperCase(), 2));
	}

	@Test
	void invalidQuantityIs400() throws Exception {
		TScene s = tscene();

		for (String quantity : List.of("0", "-1", "1000000000.001", "1.0001", "\"3\"", "null", "true")) {
			refused(s, 400, transferBody(s.w1(), s.w2(), null, s.p1(), quantity));
		}
		refused(s, 400, "{\"fromWarehouseId\": \"%s\", \"toWarehouseId\": \"%s\", \"items\": [{\"productId\": \"%s\"}]}"
			.formatted(s.w1(), s.w2(), s.p1()));
	}

	@Test
	void fractionForWholeUnitIs400() throws Exception {
		TScene s = tscene();

		refused(s, 400, transferBody(s.w1(), s.w2(), null, s.p1(), "1.5"));
		// a fractional unit takes it
		UUID kg = createProduct(s.owner(), sku(), "KG");
		stock(s, s.w1(), kg, "2.5");
		transferOk(s.owner(), transferBody(s.w1(), s.w2(), null, kg, "1.5"));
		assertThat(quantityIn(s, s.w2(), kg)).isEqualByComparingTo("1.5");
	}

	@Test
	void invalidNoteIs400() throws Exception {
		TScene s = tscene();

		refused(s, 400, transferBody(s.w1(), s.w2(), "x".repeat(501), s.p1(), 1));
		refused(s, 400, transferBody(s.w1(), s.w2(), "a\\u0000b", s.p1(), 1));
		// the boundary and a stripped blank note are fine
		String body = transferOk(s.owner(), transferBody(s.w1(), s.w2(), "x".repeat(500), s.p1(), 1));
		assertThat((String) JsonPath.read(body, "$.note")).hasSize(500);
		assertThat((Object) JsonPath.read(transferOk(s.owner(), transferBody(s.w1(), s.w2(), "   ", s.p1(), 1)),
				"$.note")).isNull();
	}

	@Test
	void unknownOrInvisibleWarehouseIs404() throws Exception {
		TScene s = tscene();
		TScene other = tscene();
		Session onlyW1 = memberWith(s, "warehouseId", s.w1());
		Session onlyW3 = memberWith(s, "warehouseId", s.w3());

		for (Object bad : List.of(UUID.randomUUID(), other.w1(), "not-a-uuid", "1-1-1-1-1")) {
			refused(s, 404, transferBody(bad, s.w2(), null, s.p1(), 1));
			refused(s, 404, transferBody(s.w1(), bad, null, s.p1(), 1));
		}
		// invisible to the member: the destination, the origin
		for (Session as : List.of(onlyW1, onlyW3)) {
			Snapshot before = snapshot(s.tenant());
			transfer(as, key(), transferBody(s.w1(), s.w2(), null, s.p1(), 1)).andExpect(status().isNotFound());
			transfer(as, key(), transferBody(s.w2(), s.w1(), null, s.p1(), 1)).andExpect(status().isNotFound());
			assertThat(snapshot(s.tenant())).isEqualTo(before);
		}
	}

	@Test
	void unknownItemProductIs404() throws Exception {
		TScene s = tscene();
		TScene other = tscene();

		for (Object bad : List.of(UUID.randomUUID(), other.p1(), "not-a-uuid")) {
			refused(s, 404, transferBody(s.w1(), s.w2(), null, s.p1(), 1, bad, 1));
			refused(s, 404, transferBody(s.w1(), s.w2(), null, bad, 1));
		}
	}

	@Test
	void archivedWarehouseOrCompanyIs409() throws Exception {
		for (String what : List.of("origin warehouse", "destination warehouse", "origin company",
				"destination company")) {
			TScene s = tscene();
			UUID to = what.startsWith("destination company") ? s.w3() : s.w2();
			switch (what) {
				case "origin warehouse" -> archive(s.owner(),
						"/api/companies/" + s.companyA() + "/warehouses/" + s.w1());
				case "destination warehouse" -> archive(s.owner(),
						"/api/companies/" + s.companyA() + "/warehouses/" + s.w2());
				case "origin company" -> archive(s.owner(), "/api/companies/" + s.companyA());
				default -> archive(s.owner(), "/api/companies/" + s.companyB());
			}

			refused(s, 409, transferBody(s.w1(), to, null, s.p1(), 1));
		}
	}

	@Test
	void archivedOrganizationIs409() throws Exception {
		TScene s = tscene();
		List<Session> as = writers(s);
		archiveOrganization(s.tenant());
		Snapshot before = snapshot(s.tenant());

		for (Session session : as) {
			transfer(session, key(), transferBody(s.w1(), s.w2(), null, s.p1(), 1)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(containsString("Organization is archived")));
		}
		assertThat(snapshot(s.tenant())).isEqualTo(before);
	}

	@Test
	void movementEndpointRejectsTransferTypes() throws Exception {
		TScene s = tscene();
		Snapshot before = snapshot(s.tenant());
		String path = s.movements(s.companyA(), s.w1());

		for (Session as : writers(s)) {
			for (String type : List.of("TRANSFER_OUT", "TRANSFER_IN")) {
				send(as, path, key(), move(type, s.p1(), 1, null)).andExpect(status().isBadRequest());
			}
		}
		assertThat(snapshot(s.tenant())).isEqualTo(before);
	}

	@Test
	void precedenceAnswersEarlierRule() throws Exception {
		TScene s = tscene();
		Session owner = s.owner();
		Session admin = joinAs(owner, "ADMIN");
		UUID unknownProduct = UUID.randomUUID();
		UUID unknownWarehouse = UUID.randomUUID();
		String emptyItems = "{\"fromWarehouseId\": \"%s\", \"toWarehouseId\": \"%s\", \"items\": []}"
			.formatted(unknownWarehouse, s.w2());
		UUID archivedProduct = createProduct(owner, sku(), "UN");
		stock(s, s.w1(), archivedProduct, 1);
		archive(owner, "/api/products/" + archivedProduct);
		UUID archivedWarehouse = createWarehouse(owner, s.companyA());
		archive(owner, "/api/companies/" + s.companyA() + "/warehouses/" + archivedWarehouse);
		UUID archivedCompany = createCompany(owner);
		UUID inArchivedCompany = createWarehouse(owner, archivedCompany);
		archive(owner, "/api/companies/" + archivedCompany + "/warehouses/" + inArchivedCompany);
		archive(owner, "/api/companies/" + archivedCompany);
		Session member = memberWith(s, "companyId", s.companyA(), s.companyB(), archivedCompany);

		for (Session as : List.of(owner, admin, member)) {
			// 400 key before 400 body
			earlier(s, as, "has space", "{\"items\": []}", 400, "Idempotency-Key", "items");
			earlier(s, as, null, "{}", 400, "Idempotency-Key", "WarehouseId");
			// 400 body before 404 warehouse
			earlier(s, as, key(), emptyItems, 400, "items", "Warehouse not found");
			// 404 warehouse before 404 product
			earlier(s, as, key(), transferBody(unknownWarehouse, s.w2(), null, unknownProduct, 1), 404,
					"Warehouse not found", "Product");
			// 404 product before 400 whole quantity
			earlier(s, as, key(), transferBody(s.w1(), s.w2(), null, s.p1(), "1.5", unknownProduct, 1), 404,
					"Product not found", "whole number");
			// 400 whole quantity before 409 archived resource (warehouse, company and product)
			earlier(s, as, key(), transferBody(s.w1(), archivedWarehouse, null, s.p1(), "1.5"), 400, "whole number",
					"archived");
			earlier(s, as, key(), transferBody(s.w1(), s.w2(), null, archivedProduct, "1.5"), 400, "whole number",
					"archived");
			// 409 archived before 409 insufficient (p2 holds 5; the archived product holds 1)
			earlier(s, as, key(), transferBody(s.w1(), s.w2(), null, archivedProduct, 1, s.p2(), 6), 409,
					"archived", "Insufficient");
			earlier(s, as, key(), transferBody(s.w1(), archivedWarehouse, null, s.p2(), 6), 409, "warehouse is archived",
					"Insufficient");
			// among the archived resources: company, then warehouse, then product
			earlier(s, as, key(), transferBody(s.w1(), inArchivedCompany, null, archivedProduct, 1), 409,
					"company is archived", "warehouse");
			earlier(s, as, key(), transferBody(s.w1(), archivedWarehouse, null, archivedProduct, 1), 409,
					"warehouse is archived", "product");
		}
		// the replay and the key reuse come after the 404 warehouse and before the 404 product and every state rule
		UUID archivedP = archivedProduct;
		for (Session as : List.of(owner, admin, member)) {
			String key = key();
			String original = transferBody(s.w1(), s.w2(), null, s.p1(), 1);
			transfer(as, key, original).andExpect(status().isCreated());
			// 400 body before the replay
			earlier(s, as, key, "{\"fromWarehouseId\": \"%s\", \"toWarehouseId\": \"%s\", \"items\": []}".formatted(s.w1(), s.w2()),
					400, "items", "Idempotency-Key was already");
			// 404 warehouse before the replay
			earlier(s, as, key, transferBody(unknownWarehouse, s.w2(), null, s.p1(), 1), 404, "Warehouse not found",
					"Idempotency-Key was already");
			// the key reuse before the 404 product, the 400 whole quantity, the 409 archived and the 409 insufficient
			for (String other : List.of(transferBody(s.w1(), s.w2(), null, unknownProduct, 1),
					transferBody(s.w1(), s.w2(), null, s.p1(), "1.5"), transferBody(s.w1(), s.w2(), null, archivedP, 1),
					transferBody(s.w1(), archivedWarehouse, null, s.p1(), 1),
					transferBody(s.w1(), s.w2(), null, s.p2(), 1000))) {
				earlier(s, as, key, other, 422, "Idempotency-Key was already", "not found");
			}
		}
		// 401 before 409 archived organization, which comes before 400 key
		archiveOrganization(s.tenant());
		for (Session as : List.of(owner, admin, member)) {
			earlier(s, as, "has space", "{}", 409, "Organization is archived", "Idempotency");
		}
		mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", "has space")
			.content("{}")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/transfers").header("Authorization", "Bearer garbage")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", "has space")
			.content("{}")).andExpect(status().isUnauthorized());
	}

	@Test
	void invalidIdempotencyKeyIs400() throws Exception {
		TScene s = tscene();
		String json = transferBody(s.w1(), s.w2(), null, s.p1(), 1);

		for (Session as : writers(s)) {
			Snapshot before = snapshot(s.tenant());
			for (String key : java.util.Arrays.asList(null, "", "x".repeat(101), "has space", "tab\there", "caf\u00e9")) {
				transfer(as, key, json).andExpect(status().isBadRequest());
			}
			assertThat(snapshot(s.tenant())).isEqualTo(before);
		}
		transfer(s.owner(), "x".repeat(100), json).andExpect(status().isCreated());
	}

	@Test
	void wholeBalanceTransferLeavesZero() throws Exception {
		TScene s = tscene();

		transferOk(s.owner(), transferBody(s.w1(), s.w2(), null, s.p1(), 10, s.p2(), 5));

		assertThat(quantityIn(s, s.w1(), s.p1())).isEqualByComparingTo("0");
		assertThat(quantityIn(s, s.w1(), s.p2())).isEqualByComparingTo("0");
		assertThat(quantityIn(s, s.w2(), s.p1())).isEqualByComparingTo("10");
		// the emptied row stays and can be moved back
		transferOk(s.owner(), transferBody(s.w2(), s.w1(), null, s.p1(), 10));
		assertThat(quantityIn(s, s.w1(), s.p1())).isEqualByComparingTo("10");
	}

	@Test
	void trailingZerosAreWhole() throws Exception {
		TScene s = tscene();

		String body = transferOk(s.owner(), transferBody(s.w1(), s.w2(), null, s.p1(), "10.000"));

		assertThat(number(body, "$.items[0].quantity")).isEqualByComparingTo("10");
		assertThat(quantityIn(s, s.w2(), s.p1())).isEqualByComparingTo("10");
	}

	@Test
	void exactly100ItemsAccepted() throws Exception {
		TScene s = tscene();
		List<Object> items = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			UUID product = createProduct(s.owner(), sku(), "UN");
			stock(s, s.w1(), product, 2);
			items.add(product);
			items.add(1);
		}

		String body = transferOk(s.owner(), transferBody(s.w1(), s.w2(), null, items.toArray()));

		assertThat((List<?>) JsonPath.read(body, "$.items")).hasSize(100);
		assertThat((String) JsonPath.read(body, "$.items[99].productId")).isEqualTo(items.get(198).toString());
		assertThat(snapshot(s.tenant()).items()).hasSize(100);
		assertThat(snapshot(s.tenant()).ledger().balances().stream().filter(b -> b.get("warehouse_id").equals(s.w2())))
			.hasSize(100)
			.allSatisfy(b -> assertThat((BigDecimal) b.get("quantity")).isEqualByComparingTo("1"));
	}

}
