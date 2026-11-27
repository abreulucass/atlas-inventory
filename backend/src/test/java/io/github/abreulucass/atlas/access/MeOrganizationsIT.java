package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

class MeOrganizationsIT extends AccessITSupport {

	static final String PATH = "/api/me/organizations";

	Map<String, Object> item(UUID id, String name, String slug, String role, String status) {
		return Map.of("id", id.toString(), "name", name, "slug", slug, "role", role, "status", status);
	}

	List<Map<String, Object>> list(Session as) throws Exception {
		String body = getAs(as, PATH).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$");
	}

	/** Organization and slug as registered, read back from the database. */
	Map<String, Object> own(Session s, String role) {
		Map<String, Object> row = app.queryForMap("select name, slug from organizations where id = ?",
				s.organizationId());
		return item(s.organizationId(), (String) row.get("name"), (String) row.get("slug"), role, "ACTIVE");
	}

	void rename(UUID organizationId, String name) {
		app.update("update organizations set name = ? where id = ?", name, organizationId);
	}

	@Test
	void listsEveryMembershipWithRoleAndStatus() throws Exception {
		Session ana = register("Ana");
		Session other = register("Other");
		addAs(other, ana.email(), "ADMIN").andExpect(status().isCreated());
		assertThat(list(ana)).containsExactlyInAnyOrder(own(ana, "OWNER"), own(other, "ADMIN"));
	}

	@Test
	void neverListsOrganizationsWithoutMembership() throws Exception {
		Session ana = register("Ana");
		Session stranger = register("Stranger");
		assertThat(app.queryForObject("select count(*) from organizations where id = ?", Integer.class,
				stranger.organizationId())).isEqualTo(1);
		assertThat(list(ana)).containsExactly(own(ana, "OWNER"));
	}

	@Test
	void ordersByNameThenSlug() throws Exception {
		Session ana = register("Ana");
		Session b = register("B");
		Session c = register("C");
		Session d = register("D");
		for (Session s : List.of(b, c, d)) {
			addAs(s, ana.email(), "MEMBER").andExpect(status().isCreated());
		}
		String unique = UUID.randomUUID().toString().substring(0, 8);
		rename(ana.organizationId(), "Zeta " + unique);
		rename(b.organizationId(), "Alpha " + unique);
		// c and d share a name: the slug breaks the tie
		rename(c.organizationId(), "Beta " + unique);
		rename(d.organizationId(), "Beta " + unique);
		List<Session> tied = List.of(c, d).stream()
			.sorted(java.util.Comparator.comparing(
					s -> (String) app.queryForObject("select slug from organizations where id = ?", String.class,
							s.organizationId())))
			.toList();
		List<Map<String, Object>> expected = List.of(own(b, "MEMBER"), own(tied.get(0), "MEMBER"), own(tied.get(1), "MEMBER"),
				own(ana, "OWNER"));
		assertThat(list(ana)).isEqualTo(expected);
	}

	@Test
	void listsArchivedOrganizationAsArchived() throws Exception {
		Session ana = register("Ana");
		Session other = register("Other");
		addAs(other, ana.email(), "MEMBER").andExpect(status().isCreated());
		archiveOrganization(other.organizationId());
		Map<String, Object> archived = new java.util.HashMap<>(own(other, "MEMBER"));
		archived.put("status", "ARCHIVED");
		assertThat(list(ana)).containsExactlyInAnyOrder(own(ana, "OWNER"), archived);
	}

	@Test
	void rejectsMissingOrInvalidToken() throws Exception {
		mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
		mvc.perform(get(PATH).header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
	}

	@Test
	void sameListWhicheverOrganizationIsActive() throws Exception {
		Session ana = register("Ana");
		Session other = register("Other");
		addAs(other, ana.email(), "ADMIN").andExpect(status().isCreated());
		List<Map<String, Object>> first = list(ana);
		List<Map<String, Object>> second = list(switchTo(ana, other.organizationId()));
		assertThat(first).hasSize(2).containsExactlyInAnyOrder(own(ana, "OWNER"), own(other, "ADMIN"));
		assertThat(second).isEqualTo(first);
		assertThat(list(switchTo(ana, ana.organizationId()))).isEqualTo(first);
	}

}
