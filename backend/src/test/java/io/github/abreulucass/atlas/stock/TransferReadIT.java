package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
			getAs(member, transferPath(within)).andExpect(status().isOk());
		}
		// w1 -> w3: seen from the origin (members 0 and 2) or from the destination company
		getAs(members.get(0), transferPath(across)).andExpect(status().isOk());
		getAs(members.get(2), transferPath(across)).andExpect(status().isOk());
		getAs(onlyDestinationCompany, transferPath(across)).andExpect(status().isOk());
		// a grant to the destination warehouse alone
		getAs(memberWith(s, "warehouseId", s.w3()), transferPath(across)).andExpect(status().isOk());
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

		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"), memberWith(s, "warehouseId", s.w1(), s.w2()))) {
			for (UUID warehouse : List.of(s.w1(), s.w2())) {
				String path = s.movements(s.companyA(), warehouse);
				String page = getAs(as, path + "?size=100").andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString();
				List<String> ids = JsonPath.read(page, "$.items[*].id");
				List<String> types = JsonPath.read(page, "$.items[*].type");
				List<String> transfers = JsonPath.read(page, "$.items[*].transferId");
				assertThat(ids).isNotEmpty().hasSameSizeAs(types);
				for (int i = 0; i < ids.size(); i++) {
					boolean transferType = types.get(i).startsWith("TRANSFER_");
					Object linked = JsonPath.read(page, "$.items[" + i + "].transferId");
					assertThat(linked).isEqualTo(transferType ? transferId : null);
					String single = getAs(as, path + "/" + ids.get(i)).andExpect(status().isOk())
						.andReturn()
						.getResponse()
						.getContentAsString();
					assertThat(JsonPath.<Object>read(single, "$.transferId")).isEqualTo(transferType ? transferId : null);
				}
				assertThat(transfers).hasSameSizeAs(ids);
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
				for (String type : List.of("TRANSFER_OUT", "TRANSFER_IN")) {
					String page = getAs(as, s.movements(s.companyA(), warehouse) + "?type=" + type)
						.andExpect(status().isOk())
						.andReturn()
						.getResponse()
						.getContentAsString();
					// each warehouse sent and received one transfer of two items
					assertThat(JsonPath.<List<String>>read(page, "$.items[*].type")).hasSize(2).containsOnly(type);
					assertThat(JsonPath.<Integer>read(page, "$.totalItems")).isEqualTo(2);
				}
			}
		}
		getAs(s.owner(), s.movements(s.companyA(), s.w1()) + "?type=BOGUS").andExpect(status().isBadRequest());
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
