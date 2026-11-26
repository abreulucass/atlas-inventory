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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

class CategoryApiIT extends CatalogITSupport {

	List<String> names(ResultActions result) throws Exception {
		return JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$[*].name");
	}

	@Test
	void createReturns201WithLocationForOwnerAndAdmin() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");

		for (Session as : List.of(owner, admin)) {
			String name = "Tools " + UUID.randomUUID();
			var response = postJson(as, "/api/categories", "{\"name\": \"  %s  \"}".formatted(name))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andReturn()
				.getResponse();
			String id = JsonPath.read(response.getContentAsString(), "$.id");
			assertThat(response.getHeader("Location")).isEqualTo("/api/categories/" + id);
			assertThat(rows(owner.organizationId(), "categories")).anySatisfy(row -> {
				assertThat(row).containsEntry("id", UUID.fromString(id)).containsEntry("name", name);
				assertThat(row).containsEntry("tenant_id", owner.organizationId()).containsEntry("archived_at", null);
			});
		}
	}

	@Test
	void createDuplicateNameReturns409() throws Exception {
		Session owner = register();
		createCategory(owner, "Tools");
		seedArchivedCategory(owner.organizationId(), "Old");
		List<Map<String, Object>> before = rows(owner.organizationId(), "categories");

		postJson(owner, "/api/categories", "{\"name\": \" TOOLS \"}").andExpect(status().isConflict());
		postJson(owner, "/api/categories", "{\"name\": \"old\"}").andExpect(status().isConflict());

		assertThat(rows(owner.organizationId(), "categories")).isEqualTo(before);
	}

	@Test
	void createInvalidNameReturns400() throws Exception {
		Session owner = register();

		for (String json : List.of("{\"name\": \"\"}", "{\"name\": \"   \"}", "{}", "{\"name\": null}",
				"{\"name\": \"%s\"}".formatted("x".repeat(81)), "not json", "", "{\"name\": 32}", "{\"name\": true}",
				"[]", "\"Tools\"")) {
			postJson(owner, "/api/categories", json).andExpect(status().isBadRequest());
		}
		assertThat(rows(owner.organizationId(), "categories")).isEmpty();

		postJson(owner, "/api/categories", "{\"name\": \"%s\"}".formatted("x".repeat(80)))
			.andExpect(status().isCreated());
		assertThat(rows(owner.organizationId(), "categories")).hasSize(1);
	}

	@Test
	void listReturnsActiveOrderedByNameForEveryRole() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");
		Session member = joinAs(owner, "MEMBER");
		createCategory(owner, "banana");
		createCategory(owner, "Apple");
		createCategory(owner, "cherry");
		seedArchivedCategory(owner.organizationId(), "Berry");
		Session other = register();
		createCategory(other, "Foreign");

		for (Session as : List.of(owner, admin, member)) {
			assertThat(names(getAs(as, "/api/categories"))).containsExactly("Apple", "banana", "cherry");
		}
	}

	@Test
	void listIncludesArchivedWhenAsked() throws Exception {
		Session owner = register();
		createCategory(owner, "banana");
		createCategory(owner, "Apple");
		seedArchivedCategory(owner.organizationId(), "Berry");

		assertThat(names(getAs(owner, "/api/categories?includeArchived=true")))
			.containsExactly("Apple", "banana", "Berry");
		getAs(owner, "/api/categories?includeArchived=true").andExpect(jsonPath("$[2].status").value("ARCHIVED"));
		assertThat(names(getAs(owner, "/api/categories?includeArchived=false"))).containsExactly("Apple", "banana");
	}

	@Test
	void getReturnsOwnCategory() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");
		Session member = joinAs(owner, "MEMBER");
		UUID active = createCategory(owner, "Tools");
		UUID archived = seedArchivedCategory(owner.organizationId(), "Old");

		for (Session as : List.of(owner, admin, member)) {
			getAs(as, "/api/categories/" + active).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(active.toString()))
				.andExpect(jsonPath("$.name").value("Tools"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
			getAs(as, "/api/categories/" + archived).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(archived.toString()))
				.andExpect(jsonPath("$.name").value("Old"))
				.andExpect(jsonPath("$.status").value("ARCHIVED"));
		}
	}

	@Test
	void archiveAndUnarchiveReturn204ForOwnerAndAdmin() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");

		for (Session as : List.of(owner, admin)) {
			UUID id = createCategory(as, "Tools " + UUID.randomUUID());

			postEmpty(as, "/api/categories/" + id + "/archive").andExpect(status().isNoContent());
			getAs(as, "/api/categories/" + id).andExpect(jsonPath("$.status").value("ARCHIVED"));
			postEmpty(as, "/api/categories/" + id + "/unarchive").andExpect(status().isNoContent());
			getAs(as, "/api/categories/" + id).andExpect(jsonPath("$.status").value("ACTIVE"));
		}
	}

	@Test
	void memberCannotWriteCategories() throws Exception {
		Session owner = register();
		Session member = joinAs(owner, "MEMBER");
		UUID id = createCategory(owner, "Tools");
		UUID archived = seedArchivedCategory(owner.organizationId(), "Old");
		List<Map<String, Object>> before = rows(owner.organizationId(), "categories");

		postJson(member, "/api/categories", "{\"name\": \"Other\"}").andExpect(status().isForbidden());
		postJson(member, "/api/categories", "{").andExpect(status().isForbidden());
		postJson(member, "/api/categories", "{\"name\": 32}").andExpect(status().isForbidden());
		postEmpty(member, "/api/categories/" + id + "/archive").andExpect(status().isForbidden());
		postEmpty(member, "/api/categories/" + archived + "/unarchive").andExpect(status().isForbidden());

		assertThat(rows(owner.organizationId(), "categories")).isEqualTo(before);
	}

	@Test
	void otherTenantUnknownOrMalformedCategoryReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID foreign = createCategory(other, "Foreign");
		UUID foreignArchived = seedArchivedCategory(other.organizationId(), "ForeignOld");
		List<Map<String, Object>> before = rows(other.organizationId(), "categories");

		for (String id : List.of(foreign.toString(), UUID.randomUUID().toString(), "not-a-uuid", "1-1-1-1-1")) {
			getAs(owner, "/api/categories/" + id).andExpect(status().isNotFound());
			postEmpty(owner, "/api/categories/" + id + "/archive").andExpect(status().isNotFound());
			postEmpty(owner, "/api/categories/" + id + "/unarchive").andExpect(status().isNotFound());
		}
		postEmpty(owner, "/api/categories/" + foreignArchived + "/unarchive").andExpect(status().isNotFound());
		getAs(owner, "/api/categories/" + foreignArchived).andExpect(status().isNotFound());
		assertThat(rows(other.organizationId(), "categories")).isEqualTo(before);
	}

	@Test
	void categoryWritesInArchivedOrganizationReturn409() throws Exception {
		Session owner = register();
		UUID id = createCategory(owner, "Tools");
		UUID archived = seedArchivedCategory(owner.organizationId(), "Old");
		archiveOrganization(owner.organizationId());
		List<Map<String, Object>> before = rows(owner.organizationId(), "categories");

		postJson(owner, "/api/categories", "{\"name\": \"Other\"}").andExpect(status().isConflict());
		postJson(owner, "/api/categories", "{").andExpect(status().isConflict());
		postJson(owner, "/api/categories", "{\"name\": 32}").andExpect(status().isConflict());
		postEmpty(owner, "/api/categories/" + id + "/archive").andExpect(status().isConflict());
		postEmpty(owner, "/api/categories/" + archived + "/unarchive").andExpect(status().isConflict());

		assertThat(rows(owner.organizationId(), "categories")).isEqualTo(before);
	}

	@Test
	void withoutTokenEveryEndpointReturns401() throws Exception {
		String id = UUID.randomUUID().toString();

		mvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/categories/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"X\"}"))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/categories/" + id + "/archive")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/categories/" + id + "/unarchive")).andExpect(status().isUnauthorized());
	}

}
