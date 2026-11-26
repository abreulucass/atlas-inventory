package io.github.abreulucass.atlas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocsDisabledIT {

	@Autowired
	MockMvc mvc;

	@Test
	void docsAre404WhenDisabled() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
		mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
	}

}
