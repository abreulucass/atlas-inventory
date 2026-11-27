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

	/** Caller owns one organization, is ADMIN in another and MEMBER in a third; a fourth is a stranger's. */
	record Fixture(Session ana, Session admin, Session member, Session stranger, List<Map<String, Object>> expected) {
	}

	Fixture fixture() throws Exception {
		Session ana = register("Ana");
		Session admin = register("Admin");
		Session member = register("Member");
		Session stranger = register("Stranger");
		addAs(admin, ana.email(), "ADMIN").andExpect(status().isCreated());
		addAs(member, ana.email(), "MEMBER").andExpect(status().isCreated());
		String unique = UUID.randomUUID().toString().substring(0, 8);
		rename(admin.organizationId(), "Alpha " + unique);
		rename(member.organizationId(), "Beta " + unique);
		rename(ana.organizationId(), "Gamma " + unique);
		rename(stranger.organizationId(), "Aaa " + unique);
		return new Fixture(ana, admin, member, stranger,
				List.of(own(admin, "ADMIN"), own(member, "MEMBER"), own(ana, "OWNER")));
	}

	@Test
	void listsEveryMembershipWithRoleAndStatus() throws Exception {
		Fixture f = fixture();
		assertThat(list(f.ana())).isEqualTo(f.expected());
	}

	@Test
	void neverListsOrganizationsWithoutMembership() throws Exception {
		Fixture f = fixture();
		UUID stranger = f.stranger().organizationId();
		assertThat(app.queryForObject("select count(*) from organizations where id = ?", Integer.class, stranger))
			.isEqualTo(1);
		for (UUID active : List.of(f.ana().organizationId(), f.admin().organizationId(),
				f.member().organizationId())) {
			List<Map<String, Object>> rows = list(switchTo(f.ana(), active));
			assertThat(rows).isEqualTo(f.expected());
			assertThat(rows).noneMatch(r -> r.get("id").equals(stranger.toString()));
		}
	}

	@Test
	void ordersByNameThenSlug() throws Exception {
		Session ana = register("Ana");
		Session b = register("B");
		String unique = UUID.randomUUID().toString().substring(0, 8);
		// c is created first but gets the later slug, so insertion order is opposite to slug order
		Session c = register("C", "zz" + unique);
		Session d = register("D", "aa" + unique);
		for (Session s : List.of(b, c, d)) {
			addAs(s, ana.email(), "MEMBER").andExpect(status().isCreated());
		}
		rename(ana.organizationId(), "Zeta " + unique);
		rename(b.organizationId(), "Alpha " + unique);
		// c and d share a name: the slug breaks the tie
		rename(c.organizationId(), "Beta " + unique);
		rename(d.organizationId(), "Beta " + unique);
		assertThat(list(ana)).isEqualTo(List.of(own(b, "MEMBER"), own(d, "MEMBER"), own(c, "MEMBER"), own(ana, "OWNER")));
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
		Fixture f = fixture();
		assertThat(list(f.ana())).isEqualTo(f.expected());
		for (Session as : List.of(f.admin(), f.member(), f.ana())) {
			assertThat(list(switchTo(f.ana(), as.organizationId()))).isEqualTo(f.expected());
		}
	}

}
