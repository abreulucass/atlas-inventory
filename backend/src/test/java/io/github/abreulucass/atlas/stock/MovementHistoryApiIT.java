package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

class MovementHistoryApiIT extends StockITSupport {

	String idOf(String body) {
		return JsonPath.read(body, "$.id");
	}

	List<String> ids(String page) {
		return JsonPath.read(page, "$.items[*].id");
	}

	String stockPath(Scene s) {
		return s.path().replace("/movements", "/stock");
	}

	String get(Session as, String path) throws Exception {
		return getAs(as, path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void getMovementReturnsBodyForEveryRoleThatSeesWarehouse() throws Exception {
		Scene s = scene();
		String created = record(s.owner(), s.path(), move("IN", s.product(), 7, "first"));
		Session admin = joinAs(s.owner(), "ADMIN");
		Session onWarehouse = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session onCompany = memberWithGrant(s.owner(), "companyId", s.company());

		for (Session as : List.of(s.owner(), admin, onWarehouse, onCompany)) {
			String body = get(as, s.path() + "/" + idOf(created));
			assertThat((String) JsonPath.read(body, "$.id")).isEqualTo(idOf(created));
			assertThat((String) JsonPath.read(body, "$.warehouseId")).isEqualTo(s.warehouse().toString());
			assertThat((String) JsonPath.read(body, "$.productId")).isEqualTo(s.product().toString());
			assertThat((String) JsonPath.read(body, "$.type")).isEqualTo("IN");
			assertThat(number(body, "$.quantity")).isEqualByComparingTo("7");
			assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("7");
			assertThat((String) JsonPath.read(body, "$.note")).isEqualTo("first");
			assertThat((String) JsonPath.read(body, "$.createdBy")).isEqualTo(s.owner().userId().toString());
			assertThat((String) JsonPath.read(body, "$.createdAt")).isEqualTo(JsonPath.read(created, "$.createdAt"));
			assertThat(JsonPath.<Object>read(body, "$.transferId")).isNull();
		}
		// a movement of another warehouse, an unknown id and a malformed id are not in this warehouse
		UUID other = createWarehouse(s.owner(), s.company());
		String elsewhere = record(s.owner(), "/api/companies/" + s.company() + "/warehouses/" + other + "/movements",
				move("IN", s.product(), 1, null));
		for (Session as : List.of(s.owner(), admin, onWarehouse, onCompany)) {
			getAs(as, s.path() + "/" + idOf(elsewhere)).andExpect(status().isNotFound());
			getAs(as, s.path() + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
			getAs(as, s.path() + "/not-a-uuid").andExpect(status().isNotFound());
		}
	}

	@Test
	void historyIsNewestFirst() throws Exception {
		Scene s = scene();
		List<String> recorded = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			recorded.add(idOf(record(s.owner(), s.path(), move("IN", s.product(), i, null))));
		}
		List<String> newestFirst = new ArrayList<>(recorded.reversed());
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "companyId", s.company());

		for (Session as : List.of(s.owner(), admin, member)) {
			String all = get(as, s.path());
			assertThat(ids(all)).isEqualTo(newestFirst);
			assertThat((Integer) JsonPath.read(all, "$.page")).isZero();
			assertThat((Integer) JsonPath.read(all, "$.size")).isEqualTo(20);
			assertThat((Integer) JsonPath.read(all, "$.totalItems")).isEqualTo(5);
			assertThat((Integer) JsonPath.read(all, "$.totalPages")).isEqualTo(1);
		}
		String first = get(s.owner(), s.path() + "?size=2");
		String second = get(s.owner(), s.path() + "?size=2&page=1");
		String third = get(s.owner(), s.path() + "?size=2&page=2");
		assertThat(ids(first)).isEqualTo(newestFirst.subList(0, 2));
		assertThat(ids(second)).isEqualTo(newestFirst.subList(2, 4));
		assertThat(ids(third)).isEqualTo(newestFirst.subList(4, 5));
		assertThat((Integer) JsonPath.read(first, "$.totalPages")).isEqualTo(3);

		// the very same createdAt on both rows; the higher id is inserted first, so neither insertion order nor
		// time can order them: only the id tiebreaker can
		java.time.OffsetDateTime tied = java.time.OffsetDateTime.now().plusDays(1);
		for (String id : List.of("00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000001")) {
			asOwnerIn(s.owner().organizationId(), """
					insert into stock_movements (id, tenant_id, company_id, warehouse_id, product_id, type, quantity,
					                             balance_after, idempotency_key, created_by, created_at)
					values (?::uuid, ?, ?, ?, ?, 'IN', 1, 100, 'tie-' || ?, ?, ?)""", id, s.owner().organizationId(),
					s.company(), s.warehouse(), s.product(), id, s.owner().userId(), tied);
		}
		assertThat(ids(get(s.owner(), s.path() + "?size=2"))).containsExactly("00000000-0000-0000-0000-000000000002",
				"00000000-0000-0000-0000-000000000001");
		assertThat(ids(get(s.owner(), s.path() + "?size=1"))).containsExactly("00000000-0000-0000-0000-000000000002");
		assertThat(ids(get(s.owner(), s.path() + "?size=1&page=1")))
			.containsExactly("00000000-0000-0000-0000-000000000001");
	}

	@Test
	void historyFiltersByProductAndType() throws Exception {
		Scene s = scene();
		UUID kg = createProduct(s.owner(), "KG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "KG");
		String inUn = idOf(record(s.owner(), s.path(), move("IN", s.product(), 10, null)));
		String outUn = idOf(record(s.owner(), s.path(), move("OUT", s.product(), 3, null)));
		String adjUn = idOf(record(s.owner(), s.path(), move("ADJUSTMENT", s.product(), -1, "lost")));
		String inKg = idOf(record(s.owner(), s.path(), move("IN", kg, "2.5", null)));
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());

		for (Session as : List.of(s.owner(), admin, member)) {
			assertThat(ids(get(as, s.path() + "?productId=" + kg))).containsExactly(inKg);
			assertThat(ids(get(as, s.path() + "?productId=" + s.product()))).containsExactly(adjUn, outUn, inUn);
			assertThat(ids(get(as, s.path() + "?type=IN"))).containsExactly(inKg, inUn);
			assertThat(ids(get(as, s.path() + "?type=OUT"))).containsExactly(outUn);
			assertThat(ids(get(as, s.path() + "?type=ADJUSTMENT&productId=" + s.product()))).containsExactly(adjUn);
			assertThat(ids(get(as, s.path() + "?type=OUT&productId=" + kg))).isEmpty();
			// a well-formed but unknown product has no movements
			String none = get(as, s.path() + "?productId=" + UUID.randomUUID());
			assertThat(ids(none)).isEmpty();
			assertThat((Integer) JsonPath.read(none, "$.totalItems")).isZero();
		}
	}

	@Test
	void invalidListParametersReturn400() throws Exception {
		Scene s = scene();
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());

		for (Session as : List.of(s.owner(), admin, member)) {
			for (String query : List.of("page=-1", "page=x", "page=", "size=0", "size=101", "size=x", "size=",
					"productId=nope", "type=SIDEWAYS", "type=in", "type=")) {
				getAs(as, s.path() + "?" + query).andExpect(status().isBadRequest());
			}
			getAs(as, s.path() + "?page=0&size=100").andExpect(status().isOk());
			// the stock listing shares the page rules; productId and type do not apply to it
			for (String query : List.of("page=-1", "page=x", "page=", "size=0", "size=101", "size=x", "size=")) {
				getAs(as, stockPath(s) + "?" + query).andExpect(status().isBadRequest());
			}
			getAs(as, stockPath(s) + "?page=0&size=100").andExpect(status().isOk());
		}
	}

	@Test
	void memberWithoutGrantCannotRead() throws Exception {
		Scene s = scene();
		String created = record(s.owner(), s.path(), move("IN", s.product(), 1, null));
		UUID sibling = createWarehouse(s.owner(), s.company());
		Session none = joinAs(s.owner(), "MEMBER");
		// a grant to a sibling warehouse says nothing about this one
		Session onSibling = memberWithGrant(s.owner(), "warehouseId", sibling);

		for (Session as : List.of(none, onSibling)) {
			getAs(as, stockPath(s)).andExpect(status().isNotFound());
			getAs(as, s.path()).andExpect(status().isNotFound());
			getAs(as, s.path() + "/" + idOf(created)).andExpect(status().isNotFound());
		}
	}

	@Test
	void archivedWarehouseStillReadable() throws Exception {
		Scene s = scene();
		String created = record(s.owner(), s.path(), move("IN", s.product(), 4, null));
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());

		archive(s.owner(), "/api/companies/" + s.company() + "/warehouses/" + s.warehouse());
		for (Session as : List.of(s.owner(), admin, member)) {
			assertThat(ids(get(as, s.path()))).containsExactly(idOf(created));
			getAs(as, s.path() + "/" + idOf(created)).andExpect(status().isOk());
			assertThat(JsonPath.<List<String>>read(get(as, stockPath(s)), "$.items[*].productId"))
				.containsExactly(s.product().toString());
		}

		// the company archived while its warehouse is active
		Scene t = scene();
		String made = record(t.owner(), t.path(), move("IN", t.product(), 4, null));
		archive(t.owner(), "/api/companies/" + t.company());
		for (Session as : List.of(t.owner())) {
			assertThat(ids(get(as, t.path()))).containsExactly(idOf(made));
			getAs(as, t.path() + "/" + idOf(made)).andExpect(status().isOk());
			assertThat(JsonPath.<List<String>>read(get(as, stockPath(t)), "$.items[*].productId"))
				.containsExactly(t.product().toString());
		}
	}

	@Test
	void movementsCannotBeEditedOrDeleted() throws Exception {
		Scene s = scene();
		String created = record(s.owner(), s.path(), move("IN", s.product(), 4, null));
		String path = s.path() + "/" + idOf(created);
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Ledger before = ledger(s.owner().organizationId());
		String stored = get(s.owner(), path);

		for (Session as : List.of(s.owner(), admin, member)) {
			String json = move("IN", s.product(), 99, "tamper");
			mvc.perform(put(path).header("Authorization", as.bearer()).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isMethodNotAllowed());
			mvc.perform(patch(path).header("Authorization", as.bearer()).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isMethodNotAllowed());
			mvc.perform(delete(path).header("Authorization", as.bearer())).andExpect(status().isMethodNotAllowed());
		}
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
		assertThat(get(s.owner(), path)).isEqualTo(stored);
	}

	@Test
	void pagePastLastReturnsEmptyItems() throws Exception {
		Scene s = scene();
		for (int i = 1; i <= 3; i++) {
			record(s.owner(), s.path(), move("IN", s.product(), i, null));
		}
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "companyId", s.company());

		for (Session as : List.of(s.owner(), admin, member)) {
			for (String page : List.of("1", "7", "99999999999999999999999")) {
				String body = get(as, s.path() + "?page=" + page);
				assertThat(ids(body)).isEmpty();
				assertThat((Integer) JsonPath.read(body, "$.totalItems")).isEqualTo(3);
				assertThat((Integer) JsonPath.read(body, "$.totalPages")).isEqualTo(1);
			}
		}
	}

	@Test
	void foreignTenantWarehouseIsNotReadable() throws Exception {
		Scene mine = scene();
		Scene foreign = scene();
		String mineCreated = record(mine.owner(), mine.path(), move("IN", mine.product(), 3, null));
		String foreignCreated = record(foreign.owner(), foreign.path(), move("IN", foreign.product(), 9, null));

		// the foreign warehouse really holds data, yet none of it is reachable from this tenant by path or id
		for (String path : List.of(foreign.path(), foreign.path() + "/" + idOf(foreignCreated), stockPath(foreign),
				"/api/companies/" + mine.company() + "/warehouses/" + foreign.warehouse() + "/movements",
				"/api/companies/" + mine.company() + "/warehouses/" + foreign.warehouse() + "/stock",
				"/api/companies/" + foreign.company() + "/warehouses/" + mine.warehouse() + "/stock")) {
			getAs(mine.owner(), path).andExpect(status().isNotFound());
		}
		// a foreign movement id is not found under this tenant's own warehouse either
		getAs(mine.owner(), mine.path() + "/" + idOf(foreignCreated)).andExpect(status().isNotFound());
		// and same-tenant reads carry nothing foreign
		String history = get(mine.owner(), mine.path());
		assertThat(ids(history)).containsExactly(idOf(mineCreated));
		assertThat(history).doesNotContain(foreign.product().toString()).doesNotContain(idOf(foreignCreated));
		String stock = get(mine.owner(), stockPath(mine));
		assertThat(JsonPath.<List<String>>read(stock, "$.items[*].productId")).containsExactly(mine.product().toString());
		assertThat(stock).doesNotContain(foreign.product().toString());
	}

}
