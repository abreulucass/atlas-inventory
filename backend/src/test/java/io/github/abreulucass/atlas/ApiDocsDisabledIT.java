package io.github.abreulucass.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Real servlet requests: MockMvc skips the container's error redispatch that once turned these 404s into 401s. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApiDocsDisabledIT {

	@LocalServerPort
	int port;

	int status(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	void assertDocsHidden() throws Exception {
		for (String path : new String[] { "/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html",
				"/swagger-ui/index.html" }) {
			assertThat(status(path)).as(path).isEqualTo(404);
		}
		// the ERROR permit must not open anything else
		assertThat(status("/api/products")).isEqualTo(401);
		assertThat(status("/api/nope")).isEqualTo(401);
	}

	@Test
	void docsAre404WhenDisabled() throws Exception {
		assertDocsHidden();
	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "ATLAS_API_DOCS_ENABLED=false")
	@Import(TestcontainersConfiguration.class)
	class ExplicitFalse {

		@LocalServerPort
		int nestedPort;

		@Test
		void docsAre404WhenExplicitlyFalse() throws Exception {
			port = nestedPort;
			assertDocsHidden();
		}

	}

}
