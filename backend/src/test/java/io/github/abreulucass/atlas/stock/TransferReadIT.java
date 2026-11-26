package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

class TransferReadIT extends TransferITSupport {

	String transferWithNote(TScene s, UUID from, UUID to) throws Exception {
		return transferOk(s.owner(), transferBody(from, to, "restock", s.p1(), 4, s.p2(), 5));
	}

	String transferPath(String body) {
		return "/api/transfers/" + JsonPath.read(body, "$.id");
	}

	String readOk(Session as, String path) throws Exception {
		return getAs(as, path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	/** The seeded movements of a warehouse as the owner persisted them: id to transfer id (null for plain ones). */
	Map<String, String> seeded(TScene s, UUID warehouse) {
		Map<String, String> expected = new HashMap<>();
		snapshot(s.tenant()).ledger()
			.entries()
			.stream()
			.filter(e -> e.get("warehouse_id").equals(warehouse))
			.forEach(e -> expected.put(e.get("id").toString(),
					e.get("transfer_id") == null ? null : e.get("transfer_id").toString()));
		return expected;
	}

	/** History and single reads of the warehouse return exactly the seeded ids, each linked as persisted. */
	void assertLinks(Session as, TScene s, UUID warehouse, Map<String, String> expected) throws Exception {
		String path = s.movements(s.companyA(), warehouse);
		String page = readOk(as, path + "?size=100");
		assertThat(JsonPath.<List<String>>read(page, "$.items[*].id")).containsExactlyInAnyOrderElementsOf(expected.keySet());
		for (int i = 0; i < expected.size(); i++) {
			String id = JsonPath.read(page, "$.items[" + i + "].id");
			assertThat(JsonPath.<Object>read(page, "$.items[" + i + "].transferId")).isEqualTo(expected.get(id));
			assertThat(JsonPath.<Object>read(readOk(as, path + "/" + id), "$.transferId")).isEqualTo(expected.get(id));
		}
	}

	@Test
	void ownerAndAdminReadTransfer() throws Exception {
		TScene s = tscene();
		String created = transferWithNote(s, s.w1(), s.w2());

		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"))) {
			String body = getAs(as, transferPath(created)).andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			assertThat(body).isEqualTo(created);
		}
		getAs(s.owner(), transferPath(created)).andExpect(jsonPath("$.fromWarehouseId").value(s.w1().toString()))
			.andExpect(jsonPath("$.note").value("restock"))
			.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()))
			.andExpect(jsonPath("$.items.length()").value(2));
	}

	@Test
	void memberSeeingEitherSideReadsTransfer() throws Exception {
		TScene s = tscene();
		String within = transferWithNote(s, s.w1(), s.w2());
		String across = transferOk(s.owner(), transferBody(s.w1(), s.w3(), "restock", s.p1(), 1));
		List<Session> members = List.of(memberWith(s, "warehouseId", s.w1()), memberWith(s, "warehouseId", s.w2()),
				memberWith(s, "companyId", s.companyA()));
		Session onlyDestinationCompany = memberWith(s, "companyId", s.companyB());

		for (Session member : members) {
			assertThat(readOk(member, transferPath(within))).isEqualTo(within);
		}
		// w1 -> w3: seen from the origin (members 0 and 2) or from the destination company
		assertThat(readOk(members.get(0), transferPath(across))).isEqualTo(across);
		assertThat(readOk(members.get(2), transferPath(across))).isEqualTo(across);
		assertThat(readOk(onlyDestinationCompany, transferPath(across))).isEqualTo(across);
		// a grant to the destination warehouse alone
		assertThat(readOk(memberWith(s, "warehouseId", s.w3()), transferPath(across))).isEqualTo(across);
		// w2 grant sees only its own side of the second transfer
		getAs(members.get(1), transferPath(across)).andExpect(status().isNotFound());
	}

	@Test
	void memberSeeingNeitherSideGets404() throws Exception {
		TScene s = tscene();
		String created = transferWithNote(s, s.w1(), s.w2());
		Snapshot before = snapshot(s.tenant());

		for (Session member : List.of(memberWith(s, "warehouseId", s.w3()), memberWith(s, "companyId", s.companyB()),
				joinAs(s.owner(), "MEMBER"))) {
			getAs(member, transferPath(created)).andExpect(status().isNotFound());
		}
		assertThat(snapshot(s.tenant())).isEqualTo(before);
	}

	@Test
	void unknownOrForeignTransferIs404() throws Exception {
		TScene s = tscene();
		TScene other = tscene();
		String foreign = transferWithNote(other, other.w1(), other.w2());
		transferWithNote(s, s.w1(), s.w2());

		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"), memberWith(s, "warehouseId", s.w1(), s.w2()))) {
			getAs(as, transferPath(foreign)).andExpect(status().isNotFound());
			getAs(as, "/api/transfers/" + UUID.randomUUID()).andExpect(status().isNotFound());
			getAs(as, "/api/transfers/not-a-uuid").andExpect(status().isNotFound());
		}
	}

	@Test
	void movementsCarryTransferId() throws Exception {
		TScene s = tscene();
		String created = transferWithNote(s, s.w1(), s.w2());
		String transferId = JsonPath.read(created, "$.id");
		record(s.owner(), s.movements(s.companyA(), s.w1()), move("OUT", s.p1(), 1, null));
		record(s.owner(), s.movements(s.companyA(), s.w1()), move("ADJUSTMENT", s.p1(), 2, "recount"));

		// the seeded set per side: IN, IN, OUT, ADJUSTMENT and the two transfer OUTs at the origin; the two INs at the destination
		Map<String, String> originSeed = seeded(s, s.w1());
		Map<String, String> destinationSeed = seeded(s, s.w2());
		assertThat(originSeed).hasSize(6);
		assertThat(originSeed.values().stream().filter(transferId::equals)).hasSize(2);
		assertThat(destinationSeed).hasSize(2).containsValue(transferId);
		assertThat(destinationSeed.values()).containsOnly(transferId);
		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"), memberWith(s, "warehouseId", s.w1(), s.w2()))) {
			for (UUID warehouse : List.of(s.w1(), s.w2())) {
				Map<String, String> expected = warehouse.equals(s.w1()) ? originSeed : destinationSeed;
				assertLinks(as, s, warehouse, expected);
				String page = readOk(as, s.movements(s.companyA(), warehouse) + "?size=100");
				List<String> types = JsonPath.read(page, "$.items[*].type");
				assertThat(types).containsExactlyInAnyOrderElementsOf(warehouse.equals(s.w1())
						? List.of("IN", "IN", "OUT", "ADJUSTMENT", "TRANSFER_OUT", "TRANSFER_OUT")
						: List.of("TRANSFER_IN", "TRANSFER_IN"));
			}
		}
		// one-sided MEMBERs: the visible warehouse links as persisted, the denied one is hidden (404) on history and single read
		for (boolean seesOrigin : List.of(true, false)) {
			Session member = memberWith(s, "warehouseId", seesOrigin ? s.w1() : s.w2());
			UUID visible = seesOrigin ? s.w1() : s.w2();
			UUID hidden = seesOrigin ? s.w2() : s.w1();
			assertLinks(member, s, visible, seesOrigin ? originSeed : destinationSeed);
			getAs(member, s.movements(s.companyA(), hidden) + "?size=100").andExpect(status().isNotFound());
			for (String id : (seesOrigin ? destinationSeed : originSeed).keySet()) {
				getAs(member, s.movements(s.companyA(), hidden) + "/" + id).andExpect(status().isNotFound());
				// the denied side's id is not readable through the visible warehouse either
				getAs(member, s.movements(s.companyA(), visible) + "/" + id).andExpect(status().isNotFound());
			}
		}
		// the movements recorded before the transfer (stock IN) carry null too
		String origin = getAs(s.owner(), s.movements(s.companyA(), s.w1()) + "?type=IN").andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<List<Object>>read(origin, "$.items[*].transferId")).containsOnlyNulls().isNotEmpty();
	}

	@Test
	void historyFiltersByTransferTypes() throws Exception {
		TScene s = tscene();
		transferWithNote(s, s.w1(), s.w2());
		transferWithNote(s, s.w2(), s.w1());

		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"), memberWith(s, "warehouseId", s.w1(), s.w2()))) {
			for (UUID warehouse : List.of(s.w1(), s.w2())) {
				assertFiltered(as, s, warehouse);
			}
		}
		// one-sided MEMBERs: the visible warehouse filters as above, the denied one answers 404
		Session onlyW1 = memberWith(s, "warehouseId", s.w1());
		Session onlyW2 = memberWith(s, "warehouseId", s.w2());
		assertFiltered(onlyW1, s, s.w1());
		assertFiltered(onlyW2, s, s.w2());
		for (String type : List.of("TRANSFER_OUT", "TRANSFER_IN")) {
			getAs(onlyW1, s.movements(s.companyA(), s.w2()) + "?type=" + type).andExpect(status().isNotFound());
			getAs(onlyW2, s.movements(s.companyA(), s.w1()) + "?type=" + type).andExpect(status().isNotFound());
		}
		getAs(s.owner(), s.movements(s.companyA(), s.w1()) + "?type=BOGUS").andExpect(status().isBadRequest());
	}

	void assertFiltered(Session as, TScene s, UUID warehouse) throws Exception {
		for (String type : List.of("TRANSFER_OUT", "TRANSFER_IN")) {
			String page = readOk(as, s.movements(s.companyA(), warehouse) + "?type=" + type);
			// each warehouse sent and received one transfer of two items
			assertThat(JsonPath.<List<String>>read(page, "$.items[*].type")).hasSize(2).containsOnly(type);
			assertThat(JsonPath.<Integer>read(page, "$.totalItems")).isEqualTo(2);
		}
	}

	@Test
	void archivedResourcesKeepTransferReadable() throws Exception {
		TScene s = tscene();
		String created = transferWithNote(s, s.w1(), s.w2());
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWith(s, "warehouseId", s.w1(), s.w2());
		archive(s.owner(), "/api/products/" + s.p1());
		archive(s.owner(), "/api/companies/" + s.companyA() + "/warehouses/" + s.w2());
		archive(s.owner(), "/api/companies/" + s.companyA() + "/warehouses/" + s.w1());
		archive(s.owner(), "/api/companies/" + s.companyA());

		for (Session as : List.of(s.owner(), admin, member)) {
			String body = getAs(as, transferPath(created)).andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			assertThat(body).isEqualTo(created);
		}
	}

	@Test
	void noEndpointEditsOrDeletesTransfer() throws Exception {
		TScene s = tscene();
		String created = transferWithNote(s, s.w1(), s.w2());
		Snapshot before = snapshot(s.tenant());

		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"), memberWith(s, "warehouseId", s.w1(), s.w2()))) {
			for (MockHttpServletRequestBuilder request : List.of(put(transferPath(created)),
					patch(transferPath(created)), delete(transferPath(created)))) {
				mvc.perform(request.header("Authorization", as.bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"note\": \"edited\"}")).andExpect(status().isMethodNotAllowed());
			}
		}
		assertThat(snapshot(s.tenant())).isEqualTo(before);
		getAs(s.owner(), transferPath(created)).andExpect(status().isOk());
	}

}
