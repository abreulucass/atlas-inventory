package io.github.abreulucass.atlas.access;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.AuthFixture.Session;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LogoutIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate app;

	@Test
	void revokesTokenAndIsIdempotent() throws Exception {
		AuthFixture f = new AuthFixture(mvc, app);
		Session ana = f.register();

		f.logout(ana.refreshToken()).andExpect(status().isNoContent());
		f.refresh(ana.refreshToken()).andExpect(status().isUnauthorized());
		f.logout(ana.refreshToken()).andExpect(status().isNoContent());
		f.logout("unknown-token").andExpect(status().isNoContent());
	}

}
