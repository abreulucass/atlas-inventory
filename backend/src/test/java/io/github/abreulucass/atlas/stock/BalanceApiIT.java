package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

class BalanceApiIT extends StockITSupport {

	String stockPath(Scene s) {
		return s.path().replace("/movements", "/stock");
	}

	String get(Session as, String path) throws Exception {
		return getAs(as, path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	record Stocked(String sku, UUID product, String unit, String quantity) {
	}

	@Test
	void stockReturnsBalancesOrderedBySkuForEveryRoleThatSeesWarehouse() throws Exception {
		Scene s = scene();
		String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
		UUID b = createProduct(s.owner(), "B" + suffix, "UN");
		UUID kg = createProduct(s.owner(), "A" + suffix, "KG");
		UUID c = createProduct(s.owner(), "C" + suffix, "UN");
		record(s.owner(), s.path(), move("IN", b, 5, null));
		record(s.owner(), s.path(), move("IN", c, 2, null));
		record(s.owner(), s.path(), move("IN", kg, "1.5", null));
		record(s.owner(), s.path(), move("OUT", b, 1, null));
		List<Stocked> expected = new ArrayList<>(List.of(new Stocked("A" + suffix, kg, "KG", "1.5"),
				new Stocked("B" + suffix, b, "UN", "4"), new Stocked("C" + suffix, c, "UN", "2")));
		// 21 stocked products in all: the default page of 20 must cut the last one off
		for (int i = 0; i < 18; i++) {
			String sku = "D%02d%s".formatted(i, suffix);
			UUID product = createProduct(s.owner(), sku, "UN");
			record(s.owner(), s.path(), move("IN", product, i + 1, null));
			expected.add(new Stocked(sku, product, "UN", String.valueOf(i + 1)));
		}
		// the product of the scene never moved here, so it is not listed
		Session admin = joinAs(s.owner(), "ADMIN");
		Session onWarehouse = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session onCompany = memberWithGrant(s.owner(), "companyId", s.company());
		Map<Object, Object> updatedAt = new HashMap<>();
		ledger(s.owner().organizationId()).balances()
			.forEach(row -> updatedAt.put(row.get("product_id"), ((Timestamp) row.get("updated_at")).toInstant()));

		for (Session as : List.of(s.owner(), admin, onWarehouse, onCompany)) {
			String body = get(as, stockPath(s));
			assertPage(body, expected.subList(0, 20), updatedAt);
			assertThat((Integer) JsonPath.read(body, "$.page")).isZero();
			assertThat((Integer) JsonPath.read(body, "$.size")).isEqualTo(20);
			assertThat((Integer) JsonPath.read(body, "$.totalItems")).isEqualTo(21);
			assertThat((Integer) JsonPath.read(body, "$.totalPages")).isEqualTo(2);
			String last = get(as, stockPath(s) + "?page=1");
			assertPage(last, expected.subList(20, 21), updatedAt);
			assertThat((Integer) JsonPath.read(last, "$.page")).isEqualTo(1);
			assertThat((Integer) JsonPath.read(last, "$.totalItems")).isEqualTo(21);
		}
		String second = get(s.owner(), stockPath(s) + "?size=2&page=1");
		assertPage(second, expected.subList(2, 4), updatedAt);
		assertThat((Integer) JsonPath.read(second, "$.totalPages")).isEqualTo(11);
		// another warehouse of the company has its own balances
		UUID other = createWarehouse(s.owner(), s.company());
		String empty = get(s.owner(), "/api/companies/" + s.company() + "/warehouses/" + other + "/stock");
		assertThat(JsonPath.<List<String>>read(empty, "$.items")).isEmpty();
	}

	/** Every item field, in order: the SKU, the product, its catalog data, the quantity and the persisted timestamp. */
	void assertPage(String body, List<Stocked> expected, Map<Object, Object> updatedAt) {
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].sku")).isEqualTo(
				expected.stream().map(Stocked::sku).toList());
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].productId")).isEqualTo(
				expected.stream().map(e -> e.product().toString()).toList());
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].name")).isEqualTo(
				expected.stream().map(e -> "Product " + e.sku()).toList());
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].unit")).isEqualTo(
				expected.stream().map(Stocked::unit).toList());
		for (int i = 0; i < expected.size(); i++) {
			assertThat(number(body, "$.items[" + i + "].quantity")).isEqualByComparingTo(expected.get(i).quantity());
			assertThat(Instant.parse(JsonPath.read(body, "$.items[" + i + "].updatedAt")))
				.isEqualTo(updatedAt.get(expected.get(i).product()));
		}
	}

	@Test
	void zeroBalanceIsListed() throws Exception {
		Scene s = scene();
		record(s.owner(), s.path(), move("IN", s.product(), 10, null));
		record(s.owner(), s.path(), move("OUT", s.product(), 10, null));
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());

		for (Session as : List.of(s.owner(), admin, member)) {
			String body = get(as, stockPath(s));
			assertThat(JsonPath.<List<String>>read(body, "$.items[*].productId")).containsExactly(
					s.product().toString());
			assertThat(number(body, "$.items[0].quantity")).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat((Integer) JsonPath.read(body, "$.totalItems")).isEqualTo(1);
		}
	}

	@Test
	void stockRejectsInvalidParamsHidesFromMemberAndReadsArchived() throws Exception {
		Scene s = scene();
		record(s.owner(), s.path(), move("IN", s.product(), 3, null));
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		UUID sibling = createWarehouse(s.owner(), s.company());
		Session none = joinAs(s.owner(), "MEMBER");
		Session onSibling = memberWithGrant(s.owner(), "warehouseId", sibling);

		for (Session as : List.of(s.owner(), admin, member)) {
			for (String query : List.of("page=-1", "page=x", "page=", "size=0", "size=101", "size=x", "size=")) {
				getAs(as, stockPath(s) + "?" + query).andExpect(status().isBadRequest());
			}
			getAs(as, stockPath(s) + "?page=0&size=100").andExpect(status().isOk());
			// a page past the last is empty with the real total
			String past = get(as, stockPath(s) + "?page=5");
			assertThat(JsonPath.<List<String>>read(past, "$.items")).isEmpty();
			assertThat((Integer) JsonPath.read(past, "$.totalItems")).isEqualTo(1);
		}
		for (Session as : List.of(none, onSibling)) {
			getAs(as, stockPath(s)).andExpect(status().isNotFound());
		}
		for (Session as : List.of(s.owner(), admin, member)) {
			String path = "/api/companies/" + s.company();
			getAs(as, path + "/warehouses/" + UUID.randomUUID() + "/stock").andExpect(status().isNotFound());
			getAs(as, path + "/warehouses/nope/stock").andExpect(status().isNotFound());
			getAs(as, "/api/companies/" + UUID.randomUUID() + "/warehouses/" + s.warehouse() + "/stock")
				.andExpect(status().isNotFound());
			// a warehouse of another company is not in this path
			getAs(as, "/api/companies/" + createCompany(s.owner()) + "/warehouses/" + s.warehouse() + "/stock")
				.andExpect(status().isNotFound());
		}

		archive(s.owner(), "/api/companies/" + s.company() + "/warehouses/" + s.warehouse());
		for (Session as : List.of(s.owner(), admin, member)) {
			assertThat((Integer) JsonPath.read(get(as, stockPath(s)), "$.totalItems")).isEqualTo(1);
		}
		Scene t = scene();
		record(t.owner(), t.path(), move("IN", t.product(), 3, null));
		archive(t.owner(), "/api/companies/" + t.company());
		assertThat((Integer) JsonPath.read(get(t.owner(), stockPath(t)), "$.totalItems")).isEqualTo(1);
	}

}
