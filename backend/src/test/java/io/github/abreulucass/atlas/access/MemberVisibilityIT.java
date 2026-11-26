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

	@Test
	void managersSeeEverything() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "ADMIN");
		UUID first = company(owner);
		UUID second = company(owner);
		UUID firstWarehouse = warehouse(owner, first);
		UUID secondWarehouse = warehouse(owner, first);
		// a grant to a manager narrows nothing
		grantWarehouseAs(owner, admin.userId(), firstWarehouse).andExpect(status().isCreated());

		for (Session manager : new Session[] { owner, admin }) {
			assertThat(ids(getAs(manager, "/api/companies"))).containsExactlyInAnyOrder(first.toString(),
					second.toString());
			assertThat(ids(getAs(manager, warehouses(first)))).containsExactlyInAnyOrder(firstWarehouse.toString(),
					secondWarehouse.toString());
			getAs(manager, companies(second)).andExpect(status().isOk());
			getAs(manager, warehouses(first) + "/" + secondWarehouse).andExpect(status().isOk());
		}
	}

	@Test
	void demotedAdminSeesOnlyGrants() throws Exception {
		Session owner = register("Olga");
		Session admin = join(owner, "ADMIN");
		UUID granted = company(owner);
		UUID ungranted = company(owner);
		grantCompanyAs(owner, admin.userId(), granted).andExpect(status().isCreated());
		assertThat(ids(getAs(admin, "/api/companies"))).containsExactlyInAnyOrder(granted.toString(),
				ungranted.toString());

		changeRoleAs(owner, admin, "MEMBER").andExpect(status().isOk());

		assertThat(ids(getAs(admin, "/api/companies"))).containsExactly(granted.toString());
		getAs(admin, companies(ungranted)).andExpect(status().isNotFound());
	}

}
