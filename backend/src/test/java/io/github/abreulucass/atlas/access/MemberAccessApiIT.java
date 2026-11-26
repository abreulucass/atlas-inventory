package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

class MemberAccessApiIT extends AccessITSupport {

	static Map<String, Object> grant(String type, UUID companyId, UUID warehouseId) {
		Map<String, Object> grant = new HashMap<>();
		grant.put("type", type);
		grant.put("companyId", companyId.toString());
		grant.put("warehouseId", warehouseId == null ? null : warehouseId.toString());
		return grant;
	}

	static Map<String, Object> body(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$");
	}

	static List<Map<String, Object>> list(ResultActions result) throws Exception {
		return JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
	}

	@Test
	void managersGrantCompany() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "ADMIN");
		Session member = join(owner, "MEMBER");
		UUID first = company(owner);
		UUID second = company(owner);

		ResultActions byOwner = grantCompanyAs(owner, member.userId(), first).andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/members/" + member.userId() + "/access/companies/" + first));
		ResultActions byAdmin = grantCompanyAs(admin, member.userId(), second).andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/members/" + member.userId() + "/access/companies/" + second));

		assertThat(body(byOwner)).isEqualTo(grant("COMPANY", first, null));
		assertThat(body(byAdmin)).isEqualTo(grant("COMPANY", second, null));
		assertThat(grantRows(owner.organizationId()))
			.extracting(r -> r.get("user_id"), r -> r.get("company_id"), r -> r.get("warehouse_id"))
			.containsExactlyInAnyOrder(tuple(member.userId(), first, null), tuple(member.userId(), second, null));
	}

	@Test
	void managersGrantWarehouse() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "ADMIN");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID first = warehouse(owner, companyId);
		UUID second = warehouse(owner, companyId);

		ResultActions byOwner = grantWarehouseAs(owner, member.userId(), first).andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/members/" + member.userId() + "/access/warehouses/" + first));
		ResultActions byAdmin = grantWarehouseAs(admin, member.userId(), second).andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/members/" + member.userId() + "/access/warehouses/" + second));

		assertThat(body(byOwner)).isEqualTo(grant("WAREHOUSE", companyId, first));
		assertThat(body(byAdmin)).isEqualTo(grant("WAREHOUSE", companyId, second));
		assertThat(grantRows(owner.organizationId()))
			.extracting(r -> r.get("user_id"), r -> r.get("company_id"), r -> r.get("warehouse_id"))
			.containsExactlyInAnyOrder(tuple(member.userId(), companyId, first),
					tuple(member.userId(), companyId, second));
	}

	@Test
	void memberCannotGrantOrRevoke() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		Session other = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);
		grantCompanyAs(owner, other.userId(), companyId).andExpect(status().isCreated());
		grantWarehouseAs(owner, other.userId(), warehouseId).andExpect(status().isCreated());
		var before = grantRows(owner.organizationId());

		grantCompanyAs(member, other.userId(), company(owner)).andExpect(status().isForbidden());
		grantWarehouseAs(member, member.userId(), warehouseId).andExpect(status().isForbidden());
		revokeCompanyAs(member, other.userId(), companyId).andExpect(status().isForbidden());
		revokeWarehouseAs(member, other.userId(), warehouseId).andExpect(status().isForbidden());

		assertThat(grantRows(owner.organizationId())).isEqualTo(before);
	}

	@Test
	void invalidGrantBodyReturns400() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);

		for (String json : List.of("{}", "{\"companyId\": null, \"warehouseId\": null}",
				"{\"companyId\": \"%s\", \"warehouseId\": \"%s\"}".formatted(companyId, warehouseId),
				"{\"companyId\": \"not-a-uuid\"}", "{\"warehouseId\": \"not-a-uuid\"}", "")) {
			grantAs(owner, member.userId(), json).andExpect(status().isBadRequest());
		}

		assertThat(grantRows(owner.organizationId())).isEmpty();
	}

	/** C32: 403, then 409, then 404 member, all before any 400 for the body. */
	@Test
	void malformedBodyYieldsToEarlierJudgements() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		Session other = join(owner, "MEMBER");
		List<String> malformed = List.of("{not json", "[]", "\"x\"", "{\"companyId\": 5}", "{}", "");

		for (String json : malformed) {
			grantAs(member, other.userId(), json).andExpect(status().isForbidden());
			grantAs(owner, UUID.randomUUID(), json).andExpect(status().isNotFound());
			grantAs(owner, "not-a-uuid", json).andExpect(status().isNotFound());
			grantAs(owner, member.userId(), json).andExpect(status().isBadRequest());
		}
		archiveOrganization(owner.organizationId());
		for (String json : malformed) {
			grantAs(owner, member.userId(), json).andExpect(status().isConflict());
			grantAs(owner, UUID.randomUUID(), json).andExpect(status().isConflict());
		}

		assertThat(grantRows(owner.organizationId())).isEmpty();
	}

	/** C33: only the canonical 8-4-4-4-12 spelling is a UUID, in path and body. */
	@Test
	void nonCanonicalUuidsAreRejected() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());
		grantWarehouseAs(owner, member.userId(), warehouseId).andExpect(status().isCreated());
		var before = grantRows(owner.organizationId());

		for (String bad : List.of("1-1-1-1-1", "1-1-1-1-" + "1".repeat(12), member.userId() + "0",
				member.userId().toString().replace("-", ""), " " + member.userId())) {
			grantCompanyAs(owner, bad, companyId).andExpect(status().isNotFound());
			readAs(owner, bad).andExpect(status().isNotFound());
			revokeCompanyAs(owner, bad, companyId).andExpect(status().isNotFound());
			revokeWarehouseAs(owner, bad, warehouseId).andExpect(status().isNotFound());
		}
		UUID other = company(owner);
		for (String bad : List.of("1-1-1-1-1", other.toString().replace("-", ""), other + "0")) {
			grantAs(owner, member.userId(), "{\"companyId\": \"%s\"}".formatted(bad)).andExpect(status().isBadRequest());
			grantAs(owner, member.userId(), "{\"warehouseId\": \"%s\"}".formatted(bad))
				.andExpect(status().isBadRequest());
			revokeCompanyAs(owner, member.userId(), bad).andExpect(status().isNotFound());
			revokeWarehouseAs(owner, member.userId(), bad).andExpect(status().isNotFound());
		}
		removeAs(owner, "1-1-1-1-1").andExpect(status().isNotFound());

		assertThat(grantRows(owner.organizationId())).isEqualTo(before);
		assertThat(list(readAs(owner, member.userId().toString().toUpperCase()))).hasSize(2);
	}

	/** C34: a MEMBER's own id is compared by value, whatever its letter case. */
	@Test
	void memberReadsOwnGrantsWithUpperCaseId() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		Session other = join(owner, "MEMBER");
		UUID companyId = company(owner);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());

		assertThat(list(readAs(member, member.userId().toString().toUpperCase())))
			.containsExactly(grant("COMPANY", companyId, null));
		readAs(member, other.userId().toString().toUpperCase()).andExpect(status().isForbidden());
		readAs(member, "1-1-1-1-1").andExpect(status().isForbidden());
	}

	@Test
	void grantOtherTenantOrUnknownTargetReturns404() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		Session stranger = register("Sam");
		UUID otherCompany = company(stranger);
		UUID otherWarehouse = warehouse(stranger, otherCompany);

		grantCompanyAs(owner, member.userId(), otherCompany).andExpect(status().isNotFound());
		grantWarehouseAs(owner, member.userId(), otherWarehouse).andExpect(status().isNotFound());
		grantCompanyAs(owner, member.userId(), UUID.randomUUID()).andExpect(status().isNotFound());
		grantWarehouseAs(owner, member.userId(), UUID.randomUUID()).andExpect(status().isNotFound());

		assertThat(grantRows(owner.organizationId())).isEmpty();
		assertThat(grantRows(stranger.organizationId())).isEmpty();
	}

	@Test
	void duplicateGrantReturns409() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());
		grantWarehouseAs(owner, member.userId(), warehouseId).andExpect(status().isCreated());
		var before = grantRows(owner.organizationId());

		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isConflict());
		grantWarehouseAs(owner, member.userId(), warehouseId).andExpect(status().isConflict());

		assertThat(grantRows(owner.organizationId())).isEqualTo(before);
	}

	@Test
	void unknownOrOtherTenantMemberReturns404() throws Exception {
		Session owner = register("Olga");
		Session stranger = register("Sam");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);

		for (Object userId : List.of(stranger.userId(), UUID.randomUUID(), "not-a-uuid")) {
			grantCompanyAs(owner, userId, companyId).andExpect(status().isNotFound());
			grantWarehouseAs(owner, userId, warehouseId).andExpect(status().isNotFound());
			readAs(owner, userId).andExpect(status().isNotFound());
			revokeCompanyAs(owner, userId, companyId).andExpect(status().isNotFound());
			revokeWarehouseAs(owner, userId, warehouseId).andExpect(status().isNotFound());
		}

		assertThat(grantRows(owner.organizationId())).isEmpty();
	}

	@Test
	void grantInArchivedOrganizationReturns409() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);
		UUID otherCompany = company(owner);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());
		grantWarehouseAs(owner, member.userId(), warehouseId).andExpect(status().isCreated());
		var before = grantRows(owner.organizationId());
		archiveOrganization(owner.organizationId());

		grantCompanyAs(owner, member.userId(), otherCompany).andExpect(status().isConflict());
		revokeCompanyAs(owner, member.userId(), companyId).andExpect(status().isConflict());
		revokeWarehouseAs(owner, member.userId(), warehouseId).andExpect(status().isConflict());

		assertThat(grantRows(owner.organizationId())).isEqualTo(before);
	}

	@Test
	void managerReadsGrantsInOrder() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "ADMIN");
		Session member = join(owner, "MEMBER");
		UUID firstCompany = company(owner);
		UUID secondCompany = company(owner);
		UUID firstWarehouse = warehouse(owner, firstCompany);
		UUID secondWarehouse = warehouse(owner, secondCompany);
		grantWarehouseAs(owner, member.userId(), firstWarehouse).andExpect(status().isCreated());
		grantCompanyAs(owner, member.userId(), firstCompany).andExpect(status().isCreated());
		grantWarehouseAs(owner, member.userId(), secondWarehouse).andExpect(status().isCreated());
		grantCompanyAs(owner, member.userId(), secondCompany).andExpect(status().isCreated());

		// company grants first, then warehouse grants, each by grant id (PostgreSQL orders uuids as hex text)
		List<Map<String, Object>> expected = grantRows(owner.organizationId()).stream()
			.sorted(Comparator.comparing((Map<String, Object> r) -> r.get("warehouse_id") != null)
				.thenComparing(r -> r.get("id").toString()))
			.map(r -> grant(r.get("warehouse_id") == null ? "COMPANY" : "WAREHOUSE", (UUID) r.get("company_id"),
					(UUID) r.get("warehouse_id")))
			.toList();

		assertThat(expected).hasSize(4);
		assertThat(list(readAs(owner, member.userId()))).isEqualTo(expected);
		assertThat(list(readAs(admin, member.userId()))).isEqualTo(expected);
		assertThat(list(readAs(owner, admin.userId()))).isEmpty();
	}

	@Test
	void memberReadsOnlyOwnGrants() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		Session other = join(owner, "MEMBER");
		UUID companyId = company(owner);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());
		grantCompanyAs(owner, other.userId(), companyId).andExpect(status().isCreated());

		assertThat(list(readAs(member, member.userId()))).containsExactly(grant("COMPANY", companyId, null));
		readAs(member, other.userId()).andExpect(status().isForbidden());
		readAs(member, owner.userId()).andExpect(status().isForbidden());
	}

	@Test
	void archivedTargetsCanBeGranted() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		archiveCompany(owner, companyId);
		UUID otherCompany = company(owner);
		UUID archivedWarehouse = warehouse(owner, otherCompany);
		archiveWarehouse(owner, otherCompany, archivedWarehouse);

		assertThat(body(grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated())))
			.isEqualTo(grant("COMPANY", companyId, null));
		assertThat(body(grantWarehouseAs(owner, member.userId(), archivedWarehouse).andExpect(status().isCreated())))
			.isEqualTo(grant("WAREHOUSE", otherCompany, archivedWarehouse));
		assertThat(grantRows(owner.organizationId())).hasSize(2);
	}

	@Test
	void revokeReturns204() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "ADMIN");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());
		grantWarehouseAs(owner, member.userId(), warehouseId).andExpect(status().isCreated());

		revokeCompanyAs(owner, member.userId(), companyId).andExpect(status().isNoContent());
		assertThat(grantRows(owner.organizationId())).extracting(r -> r.get("warehouse_id"))
			.containsExactly(warehouseId);
		revokeWarehouseAs(admin, member.userId(), warehouseId).andExpect(status().isNoContent());

		assertThat(grantRows(owner.organizationId())).isEmpty();
		assertThat(list(readAs(owner, member.userId()))).isEmpty();
	}

	@Test
	void revokeMissingGrantReturns404() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);
		// a company grant is not a grant of its warehouses, nor the other way round
		grantWarehouseAs(owner, member.userId(), warehouseId).andExpect(status().isCreated());
		var before = grantRows(owner.organizationId());

		revokeCompanyAs(owner, member.userId(), companyId).andExpect(status().isNotFound());
		revokeCompanyAs(owner, member.userId(), UUID.randomUUID()).andExpect(status().isNotFound());
		revokeCompanyAs(owner, member.userId(), "not-a-uuid").andExpect(status().isNotFound());
		revokeWarehouseAs(owner, member.userId(), UUID.randomUUID()).andExpect(status().isNotFound());
		revokeWarehouseAs(owner, member.userId(), "not-a-uuid").andExpect(status().isNotFound());

		assertThat(grantRows(owner.organizationId())).isEqualTo(before);
	}

	@Test
	void readdedMemberStartsWithoutGrants() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());
		grantWarehouseAs(owner, member.userId(), warehouseId).andExpect(status().isCreated());

		removeAs(owner, member.userId()).andExpect(status().isNoContent());
		assertThat(grantRows(owner.organizationId())).isEmpty();
		addAs(owner, member.email(), "MEMBER").andExpect(status().isCreated());

		assertThat(list(readAs(owner, member.userId()))).isEmpty();
		assertThat(grantRows(owner.organizationId())).isEmpty();
	}

	@Test
	void grantRacingRemovalLeavesNoGrant() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
		try (var memberLock = java.sql.DriverManager.getConnection(url, ownerUser, ownerPassword)) {
			memberLock.setAutoCommit(false);
			try (var lock = memberLock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
				lock.setString(1, "members:" + owner.organizationId());
				lock.executeQuery().close();
			}
			var grant = pool.submit(() -> grantCompanyAs(owner, member.userId(), companyId).andReturn().getResponse());
			awaitTrue(() -> app.queryForObject(
					"select count(*) from pg_locks where not granted and locktype = 'advisory'", Integer.class) == 1,
					"the grant to wait on the member lock");
			try (var remove = memberLock
				.prepareStatement("delete from memberships where user_id = ? and organization_id = ?")) {
				remove.setObject(1, member.userId());
				remove.setObject(2, owner.organizationId());
				assertThat(remove.executeUpdate()).isEqualTo(1);
			}
			memberLock.commit();

			assertThat(grant.get(30, java.util.concurrent.TimeUnit.SECONDS).getStatus()).isEqualTo(404);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(grantRows(owner.organizationId())).isEmpty();
	}

	void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out waiting for " + what);
			}
			Thread.sleep(20);
		}
	}

	@Test
	void unauthenticatedReturns401() throws Exception {
		UUID id = UUID.randomUUID();
		String base = "/api/members/" + id + "/access";

		mvc.perform(get(base)).andExpect(status().isUnauthorized());
		mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("{\"companyId\": \"%s\"}".formatted(id)))
			.andExpect(status().isUnauthorized());
		mvc.perform(delete(base + "/companies/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(delete(base + "/warehouses/" + id)).andExpect(status().isUnauthorized());
	}

}
