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

	@Test
	void productStockListsWarehousesAndTotal() throws Exception {
		Scene s = scene();
		UUID second = createWarehouse(s.owner(), s.company());
		UUID otherCompany = createCompany(s.owner());
		UUID third = createWarehouse(s.owner(), otherCompany);
		UUID untouched = createWarehouse(s.owner(), s.company());
		record(s.owner(), s.path(), move("IN", s.product(), 10, null));
		record(s.owner(), path(s.company(), second), move("IN", s.product(), 5, null));
		record(s.owner(), path(otherCompany, third), move("IN", s.product(), 7, null));
		record(s.owner(), path(otherCompany, third), move("OUT", s.product(), 7, null));
		Session admin = joinAs(s.owner(), "ADMIN");

		for (Session as : List.of(s.owner(), admin)) {
			String body = get(as, s.product());
			assertThat((String) JsonPath.read(body, "$.productId")).isEqualTo(s.product().toString());
			assertThat(number(body, "$.total")).isEqualByComparingTo("15");
			List<String> warehouses = JsonPath.read(body, "$.warehouses[*].warehouseId");
			// zero stays listed; a warehouse where the product never moved does not
			assertThat(warehouses).containsExactlyInAnyOrder(s.warehouse().toString(), second.toString(),
					third.toString());
			assertThat(warehouses).doesNotContain(untouched.toString());
			List<String> companies = JsonPath.read(body, "$.warehouses[*].companyId");
			assertThat(companies).isSortedAccordingTo(String::compareTo);
		}
	}

	@Test
	void memberSeesOnlyGrantedWarehouses() throws Exception {
		Scene s = scene();
		UUID hidden = createWarehouse(s.owner(), s.company());
		UUID otherCompany = createCompany(s.owner());
		UUID elsewhere = createWarehouse(s.owner(), otherCompany);
		record(s.owner(), s.path(), move("IN", s.product(), 10, null));
		record(s.owner(), path(s.company(), hidden), move("IN", s.product(), 5, null));
		record(s.owner(), path(otherCompany, elsewhere), move("IN", s.product(), 3, null));
		Session onWarehouse = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session onOtherCompany = memberWithGrant(s.owner(), "companyId", otherCompany);
		Session none = joinAs(s.owner(), "MEMBER");

		String body = get(onWarehouse, s.product());
		assertThat(JsonPath.<List<String>>read(body, "$.warehouses[*].warehouseId")).containsExactly(
				s.warehouse().toString());
		assertThat(number(body, "$.total")).isEqualByComparingTo("10");
		body = get(onOtherCompany, s.product());
		assertThat(JsonPath.<List<String>>read(body, "$.warehouses[*].warehouseId")).containsExactly(
				elsewhere.toString());
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
