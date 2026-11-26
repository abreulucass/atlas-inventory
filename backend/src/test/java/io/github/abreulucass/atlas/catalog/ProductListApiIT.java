package io.github.abreulucass.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

class ProductListApiIT extends CatalogITSupport {

	/** {@code count} active products named "Item NN" in mixed case, each with a SKU "S-NN". */
	void seedItems(UUID tenant, int count) {
		asOwnerIn(tenant, """
				insert into products (id, tenant_id, sku, name, unit)
				select gen_random_uuid(), ?, 'S-' || lpad(n::text, 2, '0'),
				       case when n % 2 = 0 then 'Item ' else 'ITEM ' end || lpad(n::text, 2, '0'), 'UN'
				from generate_series(0, ? - 1) as n""", tenant, count);
	}

	List<String> field(ResultActions result, String name) throws Exception {
		return JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.items[*]." + name);
	}

	List<String> skus(Session as, String query, Object... uriVariables) throws Exception {
		return field(mvc.perform(get("/api/products" + query, uriVariables).header("Authorization", as.bearer())),
				"sku");
	}

	@Test
	void defaultListReturnsFirst20ActiveByName() throws Exception {
		Session owner = register();
		Session member = joinAs(owner, "MEMBER");
		seedItems(owner.organizationId(), 25);
		seedProduct(owner.organizationId(), "ARCH", "Archived", "UN", null, true);
		// same name, ordered by id
		asOwnerIn(owner.organizationId(), """
				insert into products (id, tenant_id, sku, name, unit) values
				('00000000-0000-0000-0000-00000000000b', ?, 'TIE-B', 'Tie', 'UN'),
				('00000000-0000-0000-0000-00000000000a', ?, 'TIE-A', 'Tie', 'UN')""", owner.organizationId(),
				owner.organizationId());

		var result = getAs(member, "/api/products").andExpect(status().isOk())
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalItems").value(27))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.items.length()").value(20));

		List<String> expected = new java.util.ArrayList<>(
				java.util.stream.IntStream.range(0, 25).mapToObj("S-%02d"::formatted).toList());
		// "Item NN" and "ITEM NN" sort together ignoring case, so the order is by NN
		assertThat(field(result, "sku")).containsExactlyElementsOf(expected.subList(0, 20));
		assertThat(skus(member, "?page=1")).containsExactly("S-20", "S-21", "S-22", "S-23", "S-24", "TIE-A", "TIE-B");
		result.andExpect(jsonPath("$.items[0].name").value("Item 00"))
			.andExpect(jsonPath("$.items[1].name").value("ITEM 01"))
			.andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
	}

	@Test
	void pageAndSizeSelectThePage() throws Exception {
		Session owner = register();
		seedItems(owner.organizationId(), 25);

		getAs(owner, "/api/products?page=1&size=20").andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(5))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalItems").value(25))
			.andExpect(jsonPath("$.totalPages").value(2));
		assertThat(skus(owner, "?page=1&size=10"))
			.containsExactly("S-10", "S-11", "S-12", "S-13", "S-14", "S-15", "S-16", "S-17", "S-18", "S-19");
		getAs(owner, "/api/products?page=0&size=10").andExpect(jsonPath("$.totalPages").value(3));
		assertThat(skus(owner, "?size=100")).hasSize(25);
	}

	@Test
	void invalidPagingReturns400() throws Exception {
		Session owner = register();
		seedItems(owner.organizationId(), 3);

		for (String query : List.of("page=-1", "size=0", "size=101", "size=x", "page=x", "page=", "size=", "size=1.5",
				"page=99999999999")) {
			getAs(owner, "/api/products?" + query).andExpect(status().isBadRequest());
		}
		getAs(owner, "/api/products?page=0&size=1").andExpect(status().isOk());
		getAs(owner, "/api/products?size=100").andExpect(status().isOk());
	}

	@Test
	void qMatchesNameFragmentOrSkuPrefix() throws Exception {
		Session owner = register();
		UUID org = owner.organizationId();
		seedProduct(org, "ABC-1", "Blue Hammer", "UN", null, false);
		seedProduct(org, "HAM-2", "Saw", "UN", null, false);
		seedProduct(org, "X-HAM", "Nail", "UN", null, false);
		seedProduct(org, "Z-9", "Drill", "UN", null, false);

		assertThat(skus(owner, "?q=hammer")).containsExactly("ABC-1");
		// "ham" is a name fragment of Blue Hammer and the SKU prefix of HAM-2, but only a SKU suffix of X-HAM
		assertThat(skus(owner, "?q=ham")).containsExactly("ABC-1", "HAM-2");
		assertThat(skus(owner, "?q=HAM")).containsExactly("ABC-1", "HAM-2");
		assertThat(skus(owner, "?q={q}", " abc ")).containsExactly("ABC-1");
		assertThat(skus(owner, "?q=bc-1")).isEmpty();
		assertThat(skus(owner, "?q=nail")).containsExactly("X-HAM");
		assertThat(skus(owner, "?q=zzz")).isEmpty();
		assertThat(skus(owner, "?q={q}", "  ")).containsExactly("ABC-1", "Z-9", "X-HAM", "HAM-2");
		getAs(owner, "/api/products?q=ham").andExpect(jsonPath("$.totalItems").value(2));
	}

	@Test
	void categoryFilterNarrows() throws Exception {
		Session owner = register();
		UUID org = owner.organizationId();
		UUID tools = createCategory(owner, "Tools");
		UUID paint = createCategory(owner, "Paint");
		seedProduct(org, "T-1", "Hammer", "UN", tools, false);
		seedProduct(org, "T-2", "Saw", "UN", tools, false);
		seedProduct(org, "P-1", "Brush", "UN", paint, false);
		seedProduct(org, "N-1", "Loose", "UN", null, false);

		assertThat(skus(owner, "?categoryId=" + tools)).containsExactly("T-1", "T-2");
		assertThat(skus(owner, "?categoryId=" + paint)).containsExactly("P-1");
		assertThat(skus(owner, "?categoryId=" + tools + "&q=saw")).containsExactly("T-2");
	}

	@Test
	void malformedCategoryFilterReturns400() throws Exception {
		Session owner = register();

		for (String id : List.of("not-a-uuid", "1-1-1-1-1", "")) {
			getAs(owner, "/api/products?categoryId=" + id).andExpect(status().isBadRequest());
		}
	}

	@Test
	void foreignCategoryFilterReturnsEmptyPage() throws Exception {
		Session owner = register();
		Session other = register();
		seedProduct(owner.organizationId(), "T-1", "Hammer", "UN", null, false);
		UUID foreign = createCategory(other, "Foreign");
		seedProduct(other.organizationId(), "F-1", "Foreign", "UN", foreign, false);

		for (UUID id : List.of(foreign, UUID.randomUUID())) {
			getAs(owner, "/api/products?categoryId=" + id).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0))
				.andExpect(jsonPath("$.totalItems").value(0))
				.andExpect(jsonPath("$.totalPages").value(0));
		}
	}

	@Test
	void statusFilter() throws Exception {
		Session owner = register();
		UUID org = owner.organizationId();
		seedProduct(org, "A-1", "Active", "UN", null, false);
		seedProduct(org, "R-1", "Archived", "UN", null, true);

		assertThat(skus(owner, "")).containsExactly("A-1");
		assertThat(skus(owner, "?status=ACTIVE")).containsExactly("A-1");
		assertThat(skus(owner, "?status=ARCHIVED")).containsExactly("R-1");
		assertThat(skus(owner, "?status=ALL")).containsExactly("A-1", "R-1");
		getAs(owner, "/api/products?status=ARCHIVED").andExpect(jsonPath("$.items[0].status").value("ARCHIVED"));
	}

	@Test
	void invalidStatusReturns400() throws Exception {
		Session owner = register();

		for (String status : List.of("foo", "active", "")) {
			getAs(owner, "/api/products?status=" + status).andExpect(status().isBadRequest());
		}
	}

	@Test
	void listNeverShowsOtherTenant() throws Exception {
		Session owner = register();
		Session other = register();
		seedProduct(owner.organizationId(), "MINE", "Mine", "UN", null, false);
		UUID foreign = createCategory(other, "Foreign");
		seedProduct(other.organizationId(), "THEIRS", "Theirs", "UN", foreign, false);
		seedProduct(other.organizationId(), "THEIRS-OLD", "Theirs old", "UN", foreign, true);

		for (String query : List.of("", "?status=ALL", "?status=ARCHIVED", "?q=theirs", "?q=THEIRS&status=ALL",
				"?categoryId=" + foreign, "?size=100&page=0")) {
			assertThat(skus(owner, query)).as(query).doesNotContain("THEIRS", "THEIRS-OLD");
		}
		assertThat(skus(owner, "?status=ALL")).containsExactly("MINE");
		getAs(owner, "/api/products?status=ALL").andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void archivedCategoryKeepsItsProducts() throws Exception {
		Session owner = register();
		UUID category = createCategory(owner, "Tools");
		UUID product = seedProduct(owner.organizationId(), "T-1", "Hammer", "UN", category, false);

		postEmpty(owner, "/api/categories/" + category + "/archive").andExpect(status().isNoContent());

		getAs(owner, "/api/products/" + product).andExpect(jsonPath("$.categoryId").value(category.toString()));
		assertThat(skus(owner, "?categoryId=" + category)).containsExactly("T-1");
		getAs(owner, "/api/products").andExpect(jsonPath("$.items[0].categoryId").value(category.toString()));
	}

	@Test
	void qWildcardsMatchLiterally() throws Exception {
		Session owner = register();
		UUID org = owner.organizationId();
		seedProduct(org, "W1", "100% Cotton", "UN", null, false);
		seedProduct(org, "W2", "100 Cotton", "UN", null, false);
		seedProduct(org, "W3", "a_b", "UN", null, false);
		seedProduct(org, "W4", "axb", "UN", null, false);
		seedProduct(org, "W5", "back\\slash", "UN", null, false);

		assertThat(skus(owner, "?q={q}", "%")).containsExactly("W1");
		assertThat(skus(owner, "?q={q}", "_")).containsExactly("W3");
		assertThat(skus(owner, "?q={q}", "a_b")).containsExactly("W3");
		assertThat(skus(owner, "?q={q}", "\\")).containsExactly("W5");
		assertThat(skus(owner, "?q={q}", "100%")).containsExactly("W1");
	}

	@Test
	void pagePastLastReturnsEmptyItems() throws Exception {
		Session owner = register();
		seedItems(owner.organizationId(), 25);

		for (String query : List.of("page=5&size=20", "page=2&size=20")) {
			getAs(owner, "/api/products?" + query).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0))
				.andExpect(jsonPath("$.totalItems").value(25))
				.andExpect(jsonPath("$.totalPages").value(2));
		}
		// an offset beyond the int range of the query still answers an empty page
		getAs(owner, "/api/products?page=2147483647&size=100").andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0))
			.andExpect(jsonPath("$.totalItems").value(25))
			.andExpect(jsonPath("$.totalPages").value(1));
		getAs(owner, "/api/products?page=5&size=20").andExpect(jsonPath("$.page").value(5));
	}

	@Test
	void listWithoutTokenReturns401() throws Exception {
		mvc.perform(get("/api/products"))
			.andExpect(status().isUnauthorized());
	}

}
