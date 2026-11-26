package io.github.abreulucass.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ProductUpdateApiIT extends CatalogITSupport {

	String path(UUID id) {
		return "/api/products/" + id;
	}

	Map<String, Object> row(UUID tenant, UUID id) {
		return rows(tenant, "products").stream().filter(r -> r.get("id").equals(id)).findFirst().orElseThrow();
	}

	@Test
	void patchNameIncrementsVersionForOwnerAndAdmin() throws Exception {
		Session owner = register();
		Session admin = joinAs(owner, "ADMIN");
		UUID id = createProduct(owner, "A1", "Hammer", "UN");

		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"  Mallet  \"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.sku").value("A1"))
			.andExpect(jsonPath("$.name").value("Mallet"))
			.andExpect(jsonPath("$.unit").value("UN"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.version").value(1));
		assertThat(row(owner.organizationId(), id)).containsEntry("name", "Mallet").containsEntry("version", 1L);

		patchJson(admin, path(id), "{\"version\": 1, \"name\": \"Maul\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Maul"))
			.andExpect(jsonPath("$.version").value(2));
		assertThat(row(owner.organizationId(), id)).containsEntry("name", "Maul").containsEntry("version", 2L);
	}

	@Test
	void patchMovesCategory() throws Exception {
		Session owner = register();
		UUID tools = createCategory(owner, "Tools");
		UUID paint = createCategory(owner, "Paint");
		UUID id = seedProduct(owner.organizationId(), "A1", "Hammer", "UN", tools, false);

		patchJson(owner, path(id), "{\"version\": 0, \"categoryId\": \"%s\"}".formatted(paint))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.categoryId").value(paint.toString()))
			.andExpect(jsonPath("$.version").value(1));

		assertThat(row(owner.organizationId(), id)).containsEntry("category_id", paint);
	}

	@Test
	void patchNullCategoryRemovesIt() throws Exception {
		Session owner = register();
		UUID tools = createCategory(owner, "Tools");
		UUID id = seedProduct(owner.organizationId(), "A1", "Hammer", "UN", tools, false);

		patchJson(owner, path(id), "{\"version\": 0, \"categoryId\": null}").andExpect(status().isOk())
			.andExpect(jsonPath("$.categoryId").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.version").value(1));

		assertThat(row(owner.organizationId(), id)).containsEntry("category_id", null).containsEntry("name", "Hammer");
	}

	@Test
	void absentFieldsStayUnchanged() throws Exception {
		Session owner = register();
		UUID tools = createCategory(owner, "Tools");
		UUID paint = createCategory(owner, "Paint");
		UUID id = seedProduct(owner.organizationId(), "A1", "Hammer", "UN", tools, false);

		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"Mallet\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.categoryId").value(tools.toString()));
		assertThat(row(owner.organizationId(), id)).containsEntry("name", "Mallet")
			.containsEntry("category_id", tools);

		patchJson(owner, path(id), "{\"version\": 1, \"categoryId\": \"%s\"}".formatted(paint))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Mallet"));
		assertThat(row(owner.organizationId(), id)).containsEntry("name", "Mallet")
			.containsEntry("category_id", paint)
			.containsEntry("sku", "A1")
			.containsEntry("unit", "UN")
			.containsEntry("version", 2L);
	}

	@Test
	void staleVersionReturns409AndRowUnchanged() throws Exception {
		Session owner = register();
		UUID id = createProduct(owner, "A1", "Hammer", "UN");
		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"First\"}").andExpect(status().isOk());
		Map<String, Object> before = row(owner.organizationId(), id);

		// the version the client read before the first edit, and one from the future
		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"Second\"}").andExpect(status().isConflict());
		patchJson(owner, path(id), "{\"version\": 5, \"name\": \"Second\"}").andExpect(status().isConflict());
		patchJson(owner, path(id), "{\"version\": 0, \"categoryId\": null}").andExpect(status().isConflict());

		assertThat(row(owner.organizationId(), id)).isEqualTo(before);
		assertThat(rows(owner.organizationId(), "products")).hasSize(1);
	}

	@Test
	void concurrentEditLosesWith409() throws Exception {
		Session owner = register();
		UUID tenant = owner.organizationId();
		UUID id = createProduct(owner, "A1", "Hammer", "UN");
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (java.sql.Connection concurrent = java.sql.DriverManager.getConnection(url, ownerUser, ownerPassword)) {
			// the concurrent edit: bumps the version and holds the row lock until committed
			concurrent.setAutoCommit(false);
			try (var set = concurrent.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
				set.setString(1, tenant.toString());
				set.execute();
			}
			try (var edit = concurrent
				.prepareStatement("update products set name = 'Concurrent', version = version + 1 where id = ?")) {
				edit.setObject(1, id);
				assertThat(edit.executeUpdate()).isEqualTo(1);
			}
			long transaction;
			try (var statement = concurrent.prepareStatement("select txid_current()");
					var rs = statement.executeQuery()) {
				rs.next();
				transaction = rs.getLong(1);
			}

			Callable<Integer> patch = () -> patchJson(owner, path(id), "{\"version\": 0, \"name\": \"Mine\"}")
				.andReturn()
				.getResponse()
				.getStatus();
			Future<Integer> response = pool.submit(patch);
			awaitWaitingOn(transaction);
			assertThat(response.isDone()).isFalse();
			concurrent.commit();

			assertThat(response.get(30, TimeUnit.SECONDS)).isEqualTo(409);
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(row(tenant, id)).containsEntry("name", "Concurrent").containsEntry("version", 1L);
	}

	/** Waits until a backend waits for the transaction with this id to end. */
	void awaitWaitingOn(long transactionId) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (app.queryForObject(
				"select count(*) from pg_locks where not granted and locktype = 'transactionid' and transactionid::text = ?",
				Integer.class, Long.toString(transactionId)) != 1) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out waiting for a waiter on transaction " + transactionId);
			}
			Thread.sleep(20);
		}
	}

	@Test
	void missingVersionReturns400() throws Exception {
		Session owner = register();
		UUID id = createProduct(owner, "A1", "Hammer", "UN");
		Map<String, Object> before = row(owner.organizationId(), id);

		for (String json : List.of("{\"name\": \"Mallet\"}", "{\"version\": null, \"name\": \"Mallet\"}",
				"{\"version\": \"0\", \"name\": \"Mallet\"}", "{\"version\": 0.5, \"name\": \"Mallet\"}")) {
			patchJson(owner, path(id), json).andExpect(status().isBadRequest());
		}
		assertThat(row(owner.organizationId(), id)).isEqualTo(before);
	}

	@Test
	void noEditableFieldReturns400() throws Exception {
		Session owner = register();
		UUID id = createProduct(owner, "A1", "Hammer", "UN");
		Map<String, Object> before = row(owner.organizationId(), id);

		for (String json : List.of("{\"version\": 0}", "{}", "not json", "[]", "")) {
			patchJson(owner, path(id), json).andExpect(status().isBadRequest());
		}
		assertThat(row(owner.organizationId(), id)).isEqualTo(before);
	}

	@Test
	void immutableFieldsReturn400() throws Exception {
		Session owner = register();
		UUID id = createProduct(owner, "A1", "Hammer", "UN");
		Map<String, Object> before = row(owner.organizationId(), id);

		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"Mallet\", \"sku\": \"B2\"}")
			.andExpect(status().isBadRequest());
		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"Mallet\", \"unit\": \"KG\"}")
			.andExpect(status().isBadRequest());
		patchJson(owner, path(id), "{\"version\": 0, \"sku\": \"A1\"}").andExpect(status().isBadRequest());

		assertThat(row(owner.organizationId(), id)).isEqualTo(before);
	}

	@Test
	void patchArchivedProductReturns409() throws Exception {
		Session owner = register();
		UUID id = seedProduct(owner.organizationId(), "A1", "Hammer", "UN", null, true);
		Map<String, Object> before = row(owner.organizationId(), id);

		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"Mallet\"}").andExpect(status().isConflict());

		assertThat(row(owner.organizationId(), id)).isEqualTo(before);
	}

	@Test
	void patchToArchivedOrForeignCategory() throws Exception {
		Session owner = register();
		Session other = register();
		UUID tools = createCategory(owner, "Tools");
		UUID archived = seedArchivedCategory(owner.organizationId(), "Old");
		UUID foreign = createCategory(other, "Foreign");
		UUID id = seedProduct(owner.organizationId(), "A1", "Hammer", "UN", tools, false);
		Map<String, Object> before = row(owner.organizationId(), id);

		patchJson(owner, path(id), "{\"version\": 0, \"categoryId\": \"%s\"}".formatted(archived))
			.andExpect(status().isConflict());
		for (String category : List.of(foreign.toString(), UUID.randomUUID().toString(), "not-a-uuid", "1-1-1-1-1")) {
			patchJson(owner, path(id), "{\"version\": 0, \"categoryId\": \"%s\"}".formatted(category))
				.andExpect(status().isNotFound());
		}
		// a bad name (400) outranks a body category that does not exist (404)
		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"\", \"categoryId\": \"%s\"}".formatted(foreign))
			.andExpect(status().isBadRequest());

		assertThat(row(owner.organizationId(), id)).isEqualTo(before);
	}

	@Test
	void patchInvalidNameReturns400() throws Exception {
		Session owner = register();
		UUID id = createProduct(owner, "A1", "Hammer", "UN");
		Map<String, Object> before = row(owner.organizationId(), id);

		for (String name : List.of("", "   ", "x".repeat(201))) {
			patchJson(owner, path(id), "{\"version\": 0, \"name\": \"%s\"}".formatted(name))
				.andExpect(status().isBadRequest());
		}
		patchJson(owner, path(id), "{\"version\": 0, \"name\": null}").andExpect(status().isBadRequest());
		assertThat(row(owner.organizationId(), id)).isEqualTo(before);

		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"%s\"}".formatted("x".repeat(200)))
			.andExpect(status().isOk());
	}

	@Test
	void memberCannotPatch() throws Exception {
		Session owner = register();
		Session member = joinAs(owner, "MEMBER");
		UUID id = createProduct(owner, "A1", "Hammer", "UN");
		Map<String, Object> before = row(owner.organizationId(), id);

		patchJson(member, path(id), "{\"version\": 0, \"name\": \"Mallet\"}").andExpect(status().isForbidden());
		// the role check outranks the path and the body
		patchJson(member, path(UUID.randomUUID()), "{\"version\": 0, \"name\": \"Mallet\"}")
			.andExpect(status().isForbidden());
		patchJson(member, "/api/products/not-a-uuid", "not json").andExpect(status().isForbidden());

		assertThat(row(owner.organizationId(), id)).isEqualTo(before);
	}

	@Test
	void patchOtherTenantUnknownOrMalformedReturns404() throws Exception {
		Session owner = register();
		Session other = register();
		UUID foreign = createProduct(other, "FOREIGN", "Foreign", "UN");
		Map<String, Object> before = row(other.organizationId(), foreign);

		for (String id : List.of(foreign.toString(), UUID.randomUUID().toString(), "not-a-uuid", "1-1-1-1-1")) {
			patchJson(owner, "/api/products/" + id, "{\"version\": 0, \"name\": \"Mallet\"}")
				.andExpect(status().isNotFound());
			// the path resource is resolved before the body is judged
			patchJson(owner, "/api/products/" + id, "not json").andExpect(status().isNotFound());
		}
		assertThat(row(other.organizationId(), foreign)).isEqualTo(before);
	}

	@Test
	void patchInArchivedOrganizationReturns409() throws Exception {
		Session owner = register();
		UUID id = createProduct(owner, "A1", "Hammer", "UN");
		archiveOrganization(owner.organizationId());
		Map<String, Object> before = row(owner.organizationId(), id);

		patchJson(owner, path(id), "{\"version\": 0, \"name\": \"Mallet\"}").andExpect(status().isConflict());
		// the organization check outranks the path and the body
		patchJson(owner, path(UUID.randomUUID()), "not json").andExpect(status().isConflict());

		assertThat(row(owner.organizationId(), id)).isEqualTo(before);
	}

	@Test
	void patchWithoutTokenReturns401() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch(path(UUID.randomUUID()))
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"version\": 0, \"name\": \"Mallet\"}")).andExpect(status().isUnauthorized());
	}

}
