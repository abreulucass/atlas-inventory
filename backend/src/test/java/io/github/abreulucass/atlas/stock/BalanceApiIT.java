package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
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
		// the product of the scene never moved here, so it is not listed
		Session admin = joinAs(s.owner(), "ADMIN");
		Session onWarehouse = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session onCompany = memberWithGrant(s.owner(), "companyId", s.company());

		for (Session as : List.of(s.owner(), admin, onWarehouse, onCompany)) {
			String body = get(as, stockPath(s));
			assertThat(JsonPath.<List<String>>read(body, "$.items[*].sku")).containsExactly("A" + suffix,
					"B" + suffix, "C" + suffix);
			assertThat(JsonPath.<List<String>>read(body, "$.items[*].productId")).containsExactly(kg.toString(),
					b.toString(), c.toString());
			assertThat(JsonPath.<List<String>>read(body, "$.items[*].name")).containsExactly("Product A" + suffix,
					"Product B" + suffix, "Product C" + suffix);
			assertThat(JsonPath.<List<String>>read(body, "$.items[*].unit")).containsExactly("KG", "UN", "UN");
			assertThat(number(body, "$.items[0].quantity")).isEqualByComparingTo("1.5");
			assertThat(number(body, "$.items[1].quantity")).isEqualByComparingTo("4");
			assertThat(number(body, "$.items[2].quantity")).isEqualByComparingTo("2");
			assertThat((String) JsonPath.read(body, "$.items[0].updatedAt")).isNotBlank();
			assertThat((Integer) JsonPath.read(body, "$.page")).isZero();
			assertThat((Integer) JsonPath.read(body, "$.size")).isEqualTo(20);
			assertThat((Integer) JsonPath.read(body, "$.totalItems")).isEqualTo(3);
			assertThat((Integer) JsonPath.read(body, "$.totalPages")).isEqualTo(1);
		}
		String second = get(s.owner(), stockPath(s) + "?size=2&page=1");
		assertThat(JsonPath.<List<String>>read(second, "$.items[*].sku")).containsExactly("C" + suffix);
		assertThat((Integer) JsonPath.read(second, "$.totalPages")).isEqualTo(2);
		// another warehouse of the company has its own balances
		UUID other = createWarehouse(s.owner(), s.company());
		String empty = get(s.owner(), "/api/companies/" + s.company() + "/warehouses/" + other + "/stock");
		assertThat(JsonPath.<List<String>>read(empty, "$.items")).isEmpty();
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
