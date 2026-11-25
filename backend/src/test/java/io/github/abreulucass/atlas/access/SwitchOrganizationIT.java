package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.AuthFixture.Session;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SwitchOrganizationIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate app;

	@Value("${spring.datasource.url}")
	String url;

	@Value("${spring.flyway.user}")
	String ownerUser;

	@Value("${spring.flyway.password}")
	String ownerPassword;

	JdbcTemplate owner() {
		return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
	}

	ResultActions switchTo(Session s, UUID organizationId) throws Exception {
		return mvc.perform(post("/api/auth/switch-organization").header("Authorization", s.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"organizationId\": \"%s\"}".formatted(organizationId)));
	}

	@Test
	void switchesToMemberOrganization() throws Exception {
		Session ana = new AuthFixture(mvc, app).register();
		UUID second = UUID.randomUUID();
		JdbcTemplate owner = owner();
		owner.update("insert into organizations (id, name, slug) values (?, 'Second', ?)", second,
				"second-" + ana.id());
		owner.update("insert into memberships (id, user_id, organization_id, role, created_at) values (?, ?, ?, 'ADMIN', now())",
				UUID.randomUUID(), ana.userId(), second);

		String body = switchTo(ana, second).andExpect(status().isOk())
			.andExpect(jsonPath("$.refreshToken").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + JsonPath.read(body, "$.accessToken")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.organizationId").value(second.toString()))
			.andExpect(jsonPath("$.role").value("ADMIN"));
		String refreshed = new AuthFixture(mvc, app).refresh(JsonPath.read(body, "$.refreshToken"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + JsonPath.read(refreshed, "$.accessToken")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.organizationId").value(second.toString()));
	}

	@Test
	void refusesOrganizationWithoutMembership() throws Exception {
		AuthFixture f = new AuthFixture(mvc, app);
		Session ana = f.register();
		Session bob = f.register();
		int tokensBefore = app.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class,
				ana.userId());

		String foreign = switchTo(ana, bob.organizationId()).andExpect(status().isForbidden())
			.andReturn()
			.getResponse()
			.getContentAsString();
		switchTo(ana, UUID.randomUUID()).andExpect(status().isForbidden());

		assertThat(foreign).doesNotContain("accessToken");
		assertThat(app.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class,
				ana.userId())).isEqualTo(tokensBefore);
	}

}
