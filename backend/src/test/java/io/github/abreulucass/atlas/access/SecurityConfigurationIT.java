package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import io.github.abreulucass.atlas.AtlasApplication;
import io.github.abreulucass.atlas.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityConfigurationIT {

	@Autowired
	MockMvc mvc;

	@Test
	void nonPublicPathAnswers401WithoutToken() throws Exception {
		mvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
	}

	@Test
	void refusesToStartWithoutJwtSecret() {
		assertThatThrownBy(() -> new SpringApplicationBuilder(AtlasApplication.class, TestcontainersConfiguration.class)
			.web(WebApplicationType.SERVLET)
			.run("--atlas.security.jwt-secret=", "--server.port=0"))
			.hasStackTraceContaining("atlas.security.jwt-secret");
	}

}
