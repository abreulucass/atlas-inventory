package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class MemberVisibilityIT extends AccessITSupport {

	String companies(UUID companyId) {
		return "/api/companies/" + companyId;
	}

	String warehouses(UUID companyId) {
		return "/api/companies/" + companyId + "/warehouses";
	}

	@Test
	void memberWithoutGrantsSeesNothing() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		company(owner);
		company(owner);

		assertThat(ids(getAs(member, "/api/companies"))).isEmpty();
		assertThat(ids(getAs(member, "/api/companies?includeArchived=true"))).isEmpty();
	}

	@Test
	void companyGrantShowsOnlyThatCompany() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID granted = company(owner);
		company(owner);
		grantCompanyAs(owner, member.userId(), granted).andExpect(status().isCreated());

		assertThat(ids(getAs(member, "/api/companies"))).containsExactly(granted.toString());
		getAs(member, companies(granted)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(granted.toString()));
	}

	@Test
	void warehouseGrantMakesCompanyReadable() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID warehouseId = warehouse(owner, companyId);
		company(owner);
		grantWarehouseAs(owner, member.userId(), warehouseId).andExpect(status().isCreated());

		assertThat(ids(getAs(member, "/api/companies"))).containsExactly(companyId.toString());
		getAs(member, companies(companyId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(companyId.toString()));
	}

	@Test
	void companyGrantCoversLaterWarehouses() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID before = warehouse(owner, companyId);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());

		UUID after = warehouse(owner, companyId);

		assertThat(ids(getAs(member, warehouses(companyId)))).containsExactlyInAnyOrder(before.toString(),
				after.toString());
		getAs(member, warehouses(companyId) + "/" + after).andExpect(status().isOk());
	}

	@Test
	void warehouseGrantListsOnlyGrantedWarehouses() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID first = warehouse(owner, companyId);
		warehouse(owner, companyId);
		UUID third = warehouse(owner, companyId);
		grantWarehouseAs(owner, member.userId(), first).andExpect(status().isCreated());
		grantWarehouseAs(owner, member.userId(), third).andExpect(status().isCreated());

		assertThat(ids(getAs(member, warehouses(companyId)))).containsExactlyInAnyOrder(first.toString(),
				third.toString());
	}

	@Test
	void ungrantedResourcesReturn404() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID granted = company(owner);
		UUID ungranted = company(owner);
		UUID ungrantedWarehouse = warehouse(owner, ungranted);
		grantCompanyAs(owner, member.userId(), granted).andExpect(status().isCreated());

		getAs(member, companies(ungranted)).andExpect(status().isNotFound());
		getAs(member, warehouses(ungranted)).andExpect(status().isNotFound());
		getAs(member, warehouses(ungranted) + "/" + ungrantedWarehouse).andExpect(status().isNotFound());
		// a readable company does not open its ungranted warehouses
		UUID partial = company(owner);
		UUID grantedWarehouse = warehouse(owner, partial);
		UUID hiddenWarehouse = warehouse(owner, partial);
		grantWarehouseAs(owner, member.userId(), grantedWarehouse).andExpect(status().isCreated());
		getAs(member, warehouses(partial) + "/" + hiddenWarehouse).andExpect(status().isNotFound());
		getAs(member, warehouses(partial) + "/" + grantedWarehouse).andExpect(status().isOk());
	}

	@Test
	void revokeAppliesWithOldToken() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());
		assertThat(ids(getAs(member, "/api/companies"))).containsExactly(companyId.toString());

		revokeCompanyAs(owner, member.userId(), companyId).andExpect(status().isNoContent());

		// the same access token, issued before the revoke
		assertThat(ids(getAs(member, "/api/companies"))).isEmpty();
		getAs(member, companies(companyId)).andExpect(status().isNotFound());
	}

	/** C25: OWNER and ADMIN see everything, without grants and with restrictive-looking ones. */
	@Test
	void managersSeeEverything() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "ADMIN");
		UUID first = company(owner);
		UUID second = company(owner);
		UUID firstWarehouse = warehouse(owner, first);
		UUID secondWarehouse = warehouse(owner, first);

		assertManagersSeeAll(owner, admin, first, second, firstWarehouse, secondWarehouse);

		// a grant to a manager narrows nothing
		grantWarehouseAs(owner, admin.userId(), firstWarehouse).andExpect(status().isCreated());
		grantWarehouseAs(owner, owner.userId(), firstWarehouse).andExpect(status().isCreated());

		assertManagersSeeAll(owner, admin, first, second, firstWarehouse, secondWarehouse);
	}

	void assertManagersSeeAll(Session owner, Session admin, UUID first, UUID second, UUID firstWarehouse,
			UUID secondWarehouse) throws Exception {
		for (Session manager : new Session[] { owner, admin }) {
			assertThat(ids(getAs(manager, "/api/companies"))).containsExactlyInAnyOrder(first.toString(),
					second.toString());
			assertThat(ids(getAs(manager, warehouses(first)))).containsExactlyInAnyOrder(firstWarehouse.toString(),
					secondWarehouse.toString());
			assertThat(ids(getAs(manager, warehouses(second)))).isEmpty();
			getAs(manager, companies(second)).andExpect(status().isOk());
			getAs(manager, warehouses(first) + "/" + firstWarehouse).andExpect(status().isOk());
			getAs(manager, warehouses(first) + "/" + secondWarehouse).andExpect(status().isOk());
		}
	}

	@Test
	void demotedAdminSeesOnlyGrants() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "ADMIN");
		UUID granted = company(owner);
		UUID ungranted = company(owner);
		UUID grantedWarehouse = warehouse(owner, granted);
		UUID ungrantedWarehouse = warehouse(owner, ungranted);
		grantCompanyAs(owner, admin.userId(), granted).andExpect(status().isCreated());
		assertThat(ids(getAs(admin, "/api/companies"))).containsExactlyInAnyOrder(granted.toString(),
				ungranted.toString());
		assertThat(ids(getAs(admin, warehouses(ungranted)))).containsExactly(ungrantedWarehouse.toString());

		changeRoleAs(owner, admin, "MEMBER").andExpect(status().isOk());

		// the same access token, issued before the demotion
		assertThat(ids(getAs(admin, "/api/companies"))).containsExactly(granted.toString());
		getAs(admin, companies(ungranted)).andExpect(status().isNotFound());
		assertThat(ids(getAs(admin, warehouses(granted)))).containsExactly(grantedWarehouse.toString());
		getAs(admin, warehouses(granted) + "/" + grantedWarehouse).andExpect(status().isOk());
		getAs(admin, warehouses(ungranted)).andExpect(status().isNotFound());
		getAs(admin, warehouses(ungranted) + "/" + ungrantedWarehouse).andExpect(status().isNotFound());
	}

	@Test
	void revokingCompanyKeepsWarehouseGrant() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID companyId = company(owner);
		UUID kept = warehouse(owner, companyId);
		UUID other = warehouse(owner, companyId);
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());
		grantWarehouseAs(owner, member.userId(), kept).andExpect(status().isCreated());
		assertThat(ids(getAs(member, warehouses(companyId)))).hasSize(2);

		revokeCompanyAs(owner, member.userId(), companyId).andExpect(status().isNoContent());

		// the same access token, issued before the revoke
		assertThat(ids(getAs(member, "/api/companies"))).containsExactly(companyId.toString());
		assertThat(ids(getAs(member, warehouses(companyId)))).containsExactly(kept.toString());
		getAs(member, warehouses(companyId) + "/" + kept).andExpect(status().isOk());
		getAs(member, warehouses(companyId) + "/" + other).andExpect(status().isNotFound());
	}

	@Test
	void archivedRulesHoldWithinGrants() throws Exception {
		Session owner = register("Olga");
		Session member = join(owner, "MEMBER");
		UUID archivedCompany = company(owner);
		UUID companyId = company(owner);
		UUID active = warehouse(owner, companyId);
		UUID archived = warehouse(owner, companyId);
		UUID other = company(owner);
		warehouse(owner, other);
		UUID archivedGranted = warehouse(owner, other);
		UUID hiddenCompany = company(owner);
		UUID hiddenCompanyWarehouse = warehouse(owner, hiddenCompany);
		UUID hiddenArchivedWarehouse = warehouse(owner, other);
		UUID hiddenArchivedCompany = company(owner);
		archiveCompany(owner, archivedCompany);
		archiveCompany(owner, hiddenArchivedCompany);
		archiveWarehouse(owner, hiddenCompany, hiddenCompanyWarehouse);
		archiveWarehouse(owner, other, hiddenArchivedWarehouse);
		archiveWarehouse(owner, companyId, archived);
		archiveWarehouse(owner, other, archivedGranted);
		grantCompanyAs(owner, member.userId(), archivedCompany).andExpect(status().isCreated());
		grantCompanyAs(owner, member.userId(), companyId).andExpect(status().isCreated());
		grantWarehouseAs(owner, member.userId(), archivedGranted).andExpect(status().isCreated());

		assertThat(ids(getAs(member, "/api/companies"))).containsExactlyInAnyOrder(companyId.toString(),
				other.toString());
		assertThat(ids(getAs(member, "/api/companies?includeArchived=true"))).containsExactlyInAnyOrder(
				archivedCompany.toString(), companyId.toString(), other.toString());
		getAs(member, companies(archivedCompany)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"));

		assertThat(ids(getAs(member, warehouses(companyId)))).containsExactly(active.toString());
		assertThat(ids(getAs(member, warehouses(companyId) + "?includeArchived=true")))
			.containsExactlyInAnyOrder(active.toString(), archived.toString());
		getAs(member, warehouses(companyId) + "/" + archived).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"));

		// ungranted archived resources never show, with or without includeArchived
		assertThat(ids(getAs(member, "/api/companies?includeArchived=true"))).doesNotContain(
				hiddenArchivedCompany.toString(), hiddenCompany.toString());
		getAs(member, companies(hiddenArchivedCompany)).andExpect(status().isNotFound());
		getAs(member, warehouses(hiddenCompany) + "?includeArchived=true").andExpect(status().isNotFound());
		getAs(member, warehouses(hiddenCompany) + "/" + hiddenCompanyWarehouse).andExpect(status().isNotFound());
		getAs(member, warehouses(other) + "/" + hiddenArchivedWarehouse).andExpect(status().isNotFound());

		assertThat(ids(getAs(member, warehouses(other)))).isEmpty();
		assertThat(ids(getAs(member, warehouses(other) + "?includeArchived=true")))
			.containsExactly(archivedGranted.toString());
		getAs(member, warehouses(other) + "/" + archivedGranted).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

}
