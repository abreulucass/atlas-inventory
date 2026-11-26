package io.github.abreulucass.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

class ProductApiIT extends CatalogITSupport {

	String product(String sku, String name, String unit) {
		return "{\"sku\": \"%s\", \"name\": \"%s\", \"unit\": \"%s\"}".formatted(sku, name, unit);
	}

	@Test
	void createReturns201WithLocationForOwnerAndAdmin() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");

		for (Session as : List.of(owner, admin)) {
			String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
			var response = postJson(as, "/api/products", product(sku, "  Hammer  ", "UN"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.sku").value(sku))
				.andExpect(jsonPath("$.name").value("Hammer"))
				.andExpect(jsonPath("$.unit").value("UN"))
				.andExpect(jsonPath("$.fractional").value(false))
				.andExpect(jsonPath("$.categoryId").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.version").value(0))
				.andReturn()
				.getResponse();
			String id = JsonPath.read(response.getContentAsString(), "$.id");
			assertThat(response.getHeader("Location")).isEqualTo("/api/products/" + id);
			assertThat(rows(owner.organizationId(), "products")).anySatisfy(row -> {
				assertThat(row).containsEntry("id", UUID.fromString(id))
					.containsEntry("tenant_id", owner.organizationId())
					.containsEntry("sku", sku)
					.containsEntry("name", "Hammer")
					.containsEntry("unit", "UN")
					.containsEntry("category_id", null)
					.containsEntry("archived_at", null)
					.containsEntry("version", 0L);
			});
		}
	}

	@Test
	void createInCategory() throws Exception {
		Session owner = register();
		UUID category = createCategory(owner, "Tools");

		postJson(owner, "/api/products",
				"{\"sku\": \"A1\", \"name\": \"Hammer\", \"unit\": \"UN\", \"categoryId\": \"%s\"}".formatted(category))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.categoryId").value(category.toString()));

		assertThat(rows(owner.organizationId(), "products")).singleElement()
			.satisfies(row -> assertThat(row).containsEntry("category_id", category));
	}

	@Test
	void createNormalizesSku() throws Exception {
		Session owner = register();

		postJson(owner, "/api/products", product(" ab-12.x_y ", "Hammer", "UN")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.sku").value("AB-12.X_Y"));

		assertThat(rows(owner.organizationId(), "products")).singleElement()
			.satisfies(row -> assertThat(row).containsEntry("sku", "AB-12.X_Y"));
	}

	@Test
	void createDuplicateSkuReturns409() throws Exception {
		Session owner = register();
		createProduct(owner, "TOOL-1", "Hammer", "UN");
		seedProduct(owner.organizationId(), "OLD-1", "Old", "UN", null, true);
		List<Map<String, Object>> before = rows(owner.organizationId(), "products");

		postJson(owner, "/api/products", product(" tool-1 ", "Other", "KG")).andExpect(status().isConflict());
		postJson(owner, "/api/products", product("old-1", "Other", "KG")).andExpect(status().isConflict());

		assertThat(rows(owner.organizationId(), "products")).isEqualTo(before);
	}

	@Test
	void createInvalidSkuReturns400() throws Exception {
		Session owner = register();

		for (String sku : List.of("", "   ", "A".repeat(41), "AB/1")) {
			postJson(owner, "/api/products", product(sku, "Hammer", "UN")).andExpect(status().isBadRequest());
		}
		for (String json : List.of("{\"name\": \"Hammer\", \"unit\": \"UN\"}",
				"{\"sku\": null, \"name\": \"Hammer\", \"unit\": \"UN\"}",
				"{\"sku\": 12, \"name\": \"Hammer\", \"unit\": \"UN\"}")) {
			postJson(owner, "/api/products", json).andExpect(status().isBadRequest());
		}
		assertThat(rows(owner.organizationId(), "products")).isEmpty();

		postJson(owner, "/api/products", product("A".repeat(40), "Hammer", "UN")).andExpect(status().isCreated());
		assertThat(rows(owner.organizationId(), "products")).hasSize(1);
	}

	@Test
	void createInvalidNameReturns400() throws Exception {
		Session owner = register();

		for (String name : List.of("", "   ", "x".repeat(201))) {
			postJson(owner, "/api/products", product("A1", name, "UN")).andExpect(status().isBadRequest());
		}
		postJson(owner, "/api/products", "{\"sku\": \"A1\", \"unit\": \"UN\"}").andExpect(status().isBadRequest());
		for (String body : List.of("not json", "[]", "")) {
			postJson(owner, "/api/products", body).andExpect(status().isBadRequest());
		}
		assertThat(rows(owner.organizationId(), "products")).isEmpty();

		postJson(owner, "/api/products", product("A1", "x".repeat(200), "UN")).andExpect(status().isCreated());
		assertThat(rows(owner.organizationId(), "products")).hasSize(1);
	}

	@Test
	void createInvalidUnitReturns400() throws Exception {
		Session owner = register();

		postJson(owner, "/api/products", "{\"sku\": \"A1\", \"name\": \"Hammer\"}").andExpect(status().isBadRequest());
		for (String unit : List.of("XX", "", "kg")) {
			postJson(owner, "/api/products", product("A1", "Hammer", unit)).andExpect(status().isBadRequest());
		}
		assertThat(rows(owner.organizationId(), "products")).isEmpty();

		for (String unit : List.of("UN", "CX", "PC", "KG", "G", "L", "ML", "M")) {
			postJson(owner, "/api/products", product("U-" + unit, "Hammer", unit)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.unit").value(unit));
		}
		assertThat(rows(owner.organizationId(), "products")).hasSize(8);
	}

	@Test
	void fractionalFollowsUnit() throws Exception {
		Session owner = register();

		for (String unit : List.of("KG", "G", "L", "ML", "M")) {
			postJson(owner, "/api/products", product("F-" + unit, "Item", unit)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.fractional").value(true));
		}
		for (String unit : List.of("UN", "CX", "PC")) {
			postJson(owner, "/api/products", product("W-" + unit, "Item", unit)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.fractional").value(false));
			getAs(owner, "/api/products/" + createProduct(owner, "V-" + unit, "Item", unit))
				.andExpect(jsonPath("$.fractional").value(false));
		}
	}

	@Test
	void createInArchivedCategoryReturns409() throws Exception {
		Session owner = register();
		UUID archived = seedArchivedCategory(owner.organizationId(), "Old");
		List<Map<String, Object>> before = rows(owner.organizationId(), "products");

		postJson(owner, "/api/products",
				"{\"sku\": \"A1\", \"name\": \"Hammer\", \"unit\": \"UN\", \"categoryId\": \"%s\"}".formatted(archived))
			.andExpect(status().isConflict());

		assertThat(rows(owner.organizationId(), "products")).isEqualTo(before);
	}

	@Test
	void createWithOtherTenantUnknownOrMalformedCategoryReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID foreign = createCategory(other, "Foreign");
		List<Map<String, Object>> before = rows(owner.organizationId(), "products");

		for (String id : List.of(foreign.toString(), UUID.randomUUID().toString(), "not-a-uuid", "1-1-1-1-1")) {
			postJson(owner, "/api/products",
					"{\"sku\": \"A1\", \"name\": \"Hammer\", \"unit\": \"UN\", \"categoryId\": \"%s\"}".formatted(id))
				.andExpect(status().isNotFound());
		}
		assertThat(rows(owner.organizationId(), "products")).isEqualTo(before);
		// 400 outranks a body category that does not exist
		postJson(owner, "/api/products",
				"{\"sku\": \"\", \"name\": \"Hammer\", \"unit\": \"UN\", \"categoryId\": \"%s\"}".formatted(foreign))
			.andExpect(status().isBadRequest());
	}

	@Test
	void memberCannotCreateProduct() throws Exception {
		Session owner = register();
		Session member = joinAs(owner, "MEMBER");
		List<Map<String, Object>> before = rows(owner.organizationId(), "products");

		postJson(member, "/api/products", product("A1", "Hammer", "UN")).andExpect(status().isForbidden());
		// the role check outranks input validation
		postJson(member, "/api/products", "not json").andExpect(status().isForbidden());

		assertThat(before).isEmpty();
		assertThat(rows(owner.organizationId(), "products")).isEmpty();
	}

	@Test
	void createInArchivedOrganizationReturns409() throws Exception {
		Session owner = register();
		archiveOrganization(owner.organizationId());

		postJson(owner, "/api/products", product("A1", "Hammer", "UN")).andExpect(status().isConflict());
		// the organization check outranks input validation
		postJson(owner, "/api/products", "not json").andExpect(status().isConflict());

		assertThat(rows(owner.organizationId(), "products")).isEmpty();
	}

	@Test
	void sameSkuInAnotherOrganizationReturns201() throws Exception {
		Session owner = register();
		Session other = register();
		createProduct(owner, "SHARED", "Hammer", "UN");

		postJson(other, "/api/products", product("SHARED", "Hammer", "UN")).andExpect(status().isCreated());

		assertThat(rows(owner.organizationId(), "products")).hasSize(1);
		assertThat(rows(other.organizationId(), "products")).hasSize(1);
	}

	@Test
	void getReturnsOwnProductForEveryRole() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");
		Session member = joinAs(owner, "MEMBER");
		UUID category = createCategory(owner, "Tools");
		UUID active = seedProduct(owner.organizationId(), "ACT", "Active", "KG", category, false);
		UUID archived = seedProduct(owner.organizationId(), "ARC", "Archived", "UN", null, true);

		for (Session as : List.of(owner, admin, member)) {
			getAs(as, "/api/products/" + active).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(active.toString()))
				.andExpect(jsonPath("$.sku").value("ACT"))
				.andExpect(jsonPath("$.name").value("Active"))
				.andExpect(jsonPath("$.unit").value("KG"))
				.andExpect(jsonPath("$.fractional").value(true))
				.andExpect(jsonPath("$.categoryId").value(category.toString()))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.version").value(0));
			getAs(as, "/api/products/" + archived).andExpect(status().isOk())
				.andExpect(jsonPath("$.sku").value("ARC"))
				.andExpect(jsonPath("$.status").value("ARCHIVED"));
		}
	}

	@Test
	void getOtherTenantUnknownOrMalformedReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID foreign = createProduct(other, "FOREIGN", "Foreign", "UN");

		for (String id : List.of(foreign.toString(), UUID.randomUUID().toString(), "not-a-uuid", "1-1-1-1-1")) {
			getAs(owner, "/api/products/" + id).andExpect(status().isNotFound());
		}
	}

	Map<String, Object> row(UUID tenant, UUID id) {
		return rows(tenant, "products").stream().filter(r -> r.get("id").equals(id)).findFirst().orElseThrow();
	}

	@Test
	void archiveReturns204AndIncrementsVersion() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");

		for (Session as : List.of(owner, admin)) {
			UUID id = createProduct(as, "SKU-" + UUID.randomUUID().toString().substring(0, 8), "Hammer", "UN");
			Map<String, Object> before = row(owner.organizationId(), id);

			postEmpty(as, "/api/products/" + id + "/archive").andExpect(status().isNoContent());

			getAs(as, "/api/products/" + id).andExpect(jsonPath("$.status").value("ARCHIVED"))
				.andExpect(jsonPath("$.version").value(1));
			Map<String, Object> after = row(owner.organizationId(), id);
			assertThat(after.get("archived_at")).isNotNull();
			assertThat(after).containsEntry("version", 1L);
			assertThat(after).usingRecursiveComparison()
				.ignoringFields("archived_at", "version")
				.isEqualTo(before);
		}
	}

	@Test
	void unarchiveReturns204AndIncrementsVersion() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");

		for (Session as : List.of(owner, admin)) {
			UUID id = seedProduct(owner.organizationId(), "SKU-" + UUID.randomUUID().toString().substring(0, 8),
					"Hammer", "UN", null, true);
			Map<String, Object> before = row(owner.organizationId(), id);

			postEmpty(as, "/api/products/" + id + "/unarchive").andExpect(status().isNoContent());

			getAs(as, "/api/products/" + id).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.version").value(1));
			Map<String, Object> after = row(owner.organizationId(), id);
			assertThat(after).containsEntry("archived_at", null).containsEntry("version", 1L);
			assertThat(after).usingRecursiveComparison()
				.ignoringFields("archived_at", "version")
				.isEqualTo(before);
		}
	}

	@Test
	void repeatedArchiveOrUnarchiveLeavesRowUnchanged() throws Exception {
		Session owner = register();
		UUID archived = seedProduct(owner.organizationId(), "ARC", "Archived", "UN", null, true);
		UUID active = createProduct(owner, "ACT", "Active", "UN");
		Map<String, Object> archivedBefore = row(owner.organizationId(), archived);
		Map<String, Object> activeBefore = row(owner.organizationId(), active);

		postEmpty(owner, "/api/products/" + archived + "/archive").andExpect(status().isNoContent());
		postEmpty(owner, "/api/products/" + active + "/unarchive").andExpect(status().isNoContent());

		assertThat(row(owner.organizationId(), archived)).isEqualTo(archivedBefore);
		assertThat(row(owner.organizationId(), active)).isEqualTo(activeBefore);
	}

	@Test
	void memberCannotArchiveOrUnarchiveProduct() throws Exception {
		Session owner = register();
		Session member = joinAs(owner, "MEMBER");
		UUID active = createProduct(owner, "ACT", "Active", "UN");
		UUID archived = seedProduct(owner.organizationId(), "ARC", "Archived", "UN", null, true);
		List<Map<String, Object>> before = rows(owner.organizationId(), "products");

		postEmpty(member, "/api/products/" + active + "/archive").andExpect(status().isForbidden());
		postEmpty(member, "/api/products/" + archived + "/unarchive").andExpect(status().isForbidden());
		postEmpty(member, "/api/products/not-a-uuid/archive").andExpect(status().isForbidden());

		assertThat(rows(owner.organizationId(), "products")).isEqualTo(before);
	}

	@Test
	void archiveOtherTenantUnknownOrMalformedReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID foreign = createProduct(other, "FOREIGN", "Foreign", "UN");
		UUID foreignArchived = seedProduct(other.organizationId(), "FOREIGN-OLD", "Old", "UN", null, true);
		List<Map<String, Object>> before = rows(other.organizationId(), "products");

		for (String id : List.of(foreign.toString(), UUID.randomUUID().toString(), "not-a-uuid", "1-1-1-1-1")) {
			postEmpty(owner, "/api/products/" + id + "/archive").andExpect(status().isNotFound());
			postEmpty(owner, "/api/products/" + id + "/unarchive").andExpect(status().isNotFound());
		}
		postEmpty(owner, "/api/products/" + foreignArchived + "/unarchive").andExpect(status().isNotFound());
		assertThat(rows(other.organizationId(), "products")).isEqualTo(before);
	}

	@Test
	void archiveProductInArchivedOrganizationReturns409() throws Exception {
		Session owner = register();
		UUID active = createProduct(owner, "ACT", "Active", "UN");
		UUID archived = seedProduct(owner.organizationId(), "ARC", "Archived", "UN", null, true);
		archiveOrganization(owner.organizationId());
		List<Map<String, Object>> before = rows(owner.organizationId(), "products");

		postEmpty(owner, "/api/products/" + active + "/archive").andExpect(status().isConflict());
		postEmpty(owner, "/api/products/" + archived + "/unarchive").andExpect(status().isConflict());
		postEmpty(owner, "/api/products/not-a-uuid/archive").andExpect(status().isConflict());

		assertThat(rows(owner.organizationId(), "products")).isEqualTo(before);
	}

	@Test
	void withoutTokenReturns401() throws Exception {
		mvc.perform(get("/api/products/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/products/" + UUID.randomUUID() + "/archive")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/products/" + UUID.randomUUID() + "/unarchive")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(product("A1", "H", "UN")))
			.andExpect(status().isUnauthorized());
	}

}
