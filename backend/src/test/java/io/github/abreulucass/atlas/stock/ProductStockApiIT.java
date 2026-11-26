package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

class ProductStockApiIT extends StockITSupport {

	String get(Session as, UUID product) throws Exception {
		return getAs(as, "/api/products/" + product + "/stock").andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	String path(UUID company, UUID warehouse) {
		return "/api/companies/" + company + "/warehouses/" + warehouse + "/movements";
	}

	/** A warehouse entry of the product stock, with the quantity it must show. */
	record Entry(UUID company, UUID warehouse, String quantity) {
	}

	/** The entries in the order of the response, field by field. */
	void assertEntries(String body, List<Entry> expected) {
		assertThat(JsonPath.<List<String>>read(body, "$.warehouses[*].companyId"))
			.isEqualTo(expected.stream().map(e -> e.company().toString()).toList());
		assertThat(JsonPath.<List<String>>read(body, "$.warehouses[*].warehouseId"))
			.isEqualTo(expected.stream().map(e -> e.warehouse().toString()).toList());
		for (int i = 0; i < expected.size(); i++) {
			assertThat(number(body, "$.warehouses[" + i + "].quantity")).isEqualByComparingTo(expected.get(i).quantity());
		}
		assertThat(number(body, "$.total")).isEqualByComparingTo(
				expected.stream().map(e -> new java.math.BigDecimal(e.quantity())).reduce(java.math.BigDecimal.ZERO,
						java.math.BigDecimal::add));
	}

	@Test
	void productStockListsWarehousesAndTotal() throws Exception {
		Scene s = scene();
		// created out of code order; the scene's own warehouse has a W<n> code
		UUID zz = createWarehouse(s.owner(), s.company(), "ZZ");
		UUID aa = createWarehouse(s.owner(), s.company(), "AA");
		UUID mm = createWarehouse(s.owner(), s.company(), "MM");
		UUID otherCompany = createCompany(s.owner());
		UUID third = createWarehouse(s.owner(), otherCompany, "BB");
		UUID untouched = createWarehouse(s.owner(), s.company(), "NN");
		record(s.owner(), s.path(), move("IN", s.product(), 10, null));
		record(s.owner(), path(s.company(), zz), move("IN", s.product(), 4, null));
		record(s.owner(), path(s.company(), aa), move("IN", s.product(), 6, null));
		record(s.owner(), path(s.company(), mm), move("IN", s.product(), 7, null));
		record(s.owner(), path(s.company(), mm), move("OUT", s.product(), 7, null));
		record(s.owner(), path(otherCompany, third), move("IN", s.product(), 3, null));
		Session admin = joinAs(s.owner(), "ADMIN");
		// per company by code: AA, MM, W<n>, ZZ; the other company holds BB
		List<Entry> own = List.of(new Entry(s.company(), aa, "6"), new Entry(s.company(), mm, "0"),
				new Entry(s.company(), s.warehouse(), "10"), new Entry(s.company(), zz, "4"));
		List<Entry> all = new java.util.ArrayList<>();
		for (UUID company : java.util.stream.Stream.of(s.company(), otherCompany)
			.sorted(java.util.Comparator.comparing(UUID::toString))
			.toList()) {
			all.addAll(company.equals(s.company()) ? own : List.of(new Entry(otherCompany, third, "3")));
		}

		for (Session as : List.of(s.owner(), admin)) {
			String body = get(as, s.product());
			assertThat((String) JsonPath.read(body, "$.productId")).isEqualTo(s.product().toString());
			// zero stays listed; a warehouse where the product never moved does not
			assertEntries(body, all);
			assertThat(number(body, "$.total")).isEqualByComparingTo("23");
			assertThat(JsonPath.<List<String>>read(body, "$.warehouses[*].warehouseId"))
				.doesNotContain(untouched.toString());
		}
	}

	@Test
	void memberSeesOnlyGrantedWarehouses() throws Exception {
		Scene s = scene();
		UUID hidden = createWarehouse(s.owner(), s.company(), "AA");
		UUID late = createWarehouse(s.owner(), s.company(), "ZZ");
		UUID otherCompany = createCompany(s.owner());
		UUID elsewhere = createWarehouse(s.owner(), otherCompany, "BB");
		record(s.owner(), s.path(), move("IN", s.product(), 10, null));
		record(s.owner(), path(s.company(), hidden), move("IN", s.product(), 5, null));
		record(s.owner(), path(s.company(), late), move("IN", s.product(), 4, null));
		record(s.owner(), path(otherCompany, elsewhere), move("IN", s.product(), 3, null));
		Session onWarehouse = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session onCompany = memberWithGrant(s.owner(), "companyId", s.company());
		Session onOtherCompany = memberWithGrant(s.owner(), "companyId", otherCompany);
		Session none = joinAs(s.owner(), "MEMBER");

		String body = get(onWarehouse, s.product());
		assertEntries(body, List.of(new Entry(s.company(), s.warehouse(), "10")));
		assertThat(number(body, "$.total")).isEqualByComparingTo("10");
		// a company grant sees every stocked warehouse of the company, ordered by code, and not the other company
		body = get(onCompany, s.product());
		assertEntries(body, List.of(new Entry(s.company(), hidden, "5"), new Entry(s.company(), s.warehouse(), "10"),
				new Entry(s.company(), late, "4")));
		assertThat(number(body, "$.total")).isEqualByComparingTo("19");
		body = get(onOtherCompany, s.product());
		assertEntries(body, List.of(new Entry(otherCompany, elsewhere, "3")));
		assertThat(number(body, "$.total")).isEqualByComparingTo("3");
		body = get(none, s.product());
		assertThat(JsonPath.<List<String>>read(body, "$.warehouses")).isEmpty();
		assertThat(number(body, "$.total")).isEqualByComparingTo("0");
	}

	@Test
	void productWithoutStockReturnsZero() throws Exception {
		Scene s = scene();
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session none = joinAs(s.owner(), "MEMBER");

		for (Session as : List.of(s.owner(), admin, member, none)) {
			String body = get(as, s.product());
			assertThat(number(body, "$.total")).isEqualByComparingTo("0");
			assertThat(JsonPath.<List<String>>read(body, "$.warehouses")).isEmpty();
		}
	}

	@Test
	void invalidProductReturns404() throws Exception {
		Scene s = scene();
		Scene other = scene();
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session none = joinAs(s.owner(), "MEMBER");

		for (Session as : List.of(s.owner(), admin, member, none)) {
			for (String id : List.of(other.product().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
				getAs(as, "/api/products/" + id + "/stock").andExpect(status().isNotFound());
			}
		}
	}

}
