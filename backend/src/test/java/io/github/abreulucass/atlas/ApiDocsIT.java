package io.github.abreulucass.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

@SpringBootTest(properties = "ATLAS_API_DOCS_ENABLED=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocsIT {

	static final List<String> PUBLIC_AUTH = List.of("/api/auth/register", "/api/auth/login", "/api/auth/refresh",
			"/api/auth/logout");

	@Autowired
	MockMvc mvc;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	DocumentContext docs() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
		return JsonPath.using(Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS)).parse(body);
	}

	@Test
	void apiDocsArePublicWhenEnabled() throws Exception {
		DocumentContext doc = docs();
		assertThat(doc.read("$.openapi", String.class)).startsWith("3.");
	}

	@Test
	void swaggerUiIsPublicWhenEnabled() throws Exception {
		String location = mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection()).andReturn()
			.getResponse().getRedirectedUrl();
		String page = mvc.perform(get(location)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
		assertThat(page).contains("Swagger UI");
	}

	@Test
	void documentListsEveryApiOperation() throws Exception {
		DocumentContext doc = docs();
		Set<String> expected = new HashSet<>();
		for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
			RequestMappingInfo info = entry.getKey();
			HandlerMethod method = entry.getValue();
			if (!method.getBeanType().isAnnotationPresent(RestController.class)) {
				continue;
			}
			for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
				if (pattern.startsWith("/api")) {
					info.getMethodsCondition().getMethods()
						.forEach(m -> expected.add(m.name().toLowerCase() + " " + pattern));
				}
			}
		}
		assertThat(expected).isNotEmpty();
		Set<String> documented = new HashSet<>();
		java.util.Map<String, java.util.Map<String, Object>> paths = doc.read("$.paths");
		paths.forEach((path, ops) -> ops.keySet().forEach(m -> documented.add(m + " " + path)));
		assertThat(documented).containsAll(expected);
	}

	@Test
	void bearerAuthOnEveryProtectedOperation() throws Exception {
		DocumentContext doc = docs();
		assertThat(doc.read("$.components.securitySchemes.bearerAuth.type", String.class)).isEqualTo("http");
		assertThat(doc.read("$.components.securitySchemes.bearerAuth.scheme", String.class)).isEqualTo("bearer");
		java.util.Map<String, java.util.Map<String, java.util.Map<String, Object>>> paths = doc.read("$.paths");
		int protectedOps = 0;
		for (var path : paths.entrySet()) {
			for (var op : path.getValue().entrySet()) {
				List<?> security = (List<?>) op.getValue().get("security");
				boolean open = PUBLIC_AUTH.contains(path.getKey()) && op.getKey().equals("post");
				if (open) {
					assertThat(security).as(path.getKey()).isNullOrEmpty();
				}
				else if (path.getKey().startsWith("/api")) {
					protectedOps++;
					// global requirement or an explicit per-operation one
					List<?> effective = security != null ? security : doc.read("$.security");
					assertThat(effective.toString()).as(op.getKey() + " " + path.getKey()).contains("bearerAuth");
				}
			}
		}
		assertThat(protectedOps).isPositive();
	}

	@Test
	void idempotencyKeyRequiredOnMovementAndTransfer() throws Exception {
		DocumentContext doc = docs();
		for (String path : List.of("/api/companies/{companyId}/warehouses/{warehouseId}/movements",
				"/api/transfers")) {
			List<Boolean> required = doc.read(
					"$.paths['" + path + "'].post.parameters[?(@.name=='Idempotency-Key' && @.in=='header')].required");
			assertThat(required).as(path).containsExactly(true);
		}
	}

	@Test
	void rawBodiesHaveObjectSchemas() throws Exception {
		DocumentContext doc = docs();
		assertBody(doc, "/api/products", "post", List.of("sku", "name", "unit", "categoryId"),
				List.of("sku", "name", "unit"));
		assertBody(doc, "/api/products/{id}", "patch", List.of("version", "name", "categoryId"), List.of("version"));
		assertBody(doc, "/api/categories", "post", List.of("name"), List.of("name"));
		assertBody(doc, "/api/members/{userId}/access", "post", List.of("companyId", "warehouseId"), List.of());
		assertBody(doc, "/api/companies/{companyId}/warehouses/{warehouseId}/movements", "post",
				List.of("type", "productId", "quantity", "note"), List.of("type", "productId", "quantity"));
		assertBody(doc, "/api/transfers", "post", List.of("fromWarehouseId", "toWarehouseId", "note", "items"),
				List.of("fromWarehouseId", "toWarehouseId", "items"));
		assertThat(doc.read("$.paths['/api/transfers'].post.requestBody.content['application/json'].schema.$ref",
				String.class)).isEqualTo("#/components/schemas/TransferBody");
		assertThat(doc.read("$.components.schemas.TransferBody.properties.items.items.$ref", String.class))
			.isEqualTo("#/components/schemas/TransferItemBody");
		assertThat(doc.read("$.components.schemas.TransferItemBody.required", List.class))
			.containsExactlyInAnyOrder("productId", "quantity");
	}

	private static void assertBody(DocumentContext doc, String path, String method, List<String> properties,
			List<String> required) {
		String ref = doc.read("$.paths['" + path + "']." + method + ".requestBody.content['application/json'].schema.$ref",
				String.class);
		String schema = ref.substring(ref.lastIndexOf('/') + 1);
		java.util.Map<String, Object> props = doc.read("$.components.schemas." + schema + ".properties");
		assertThat(props.keySet()).as(path).containsExactlyInAnyOrderElementsOf(properties);
		List<String> req = doc.read("$.components.schemas." + schema + ".required");
		assertThat(req == null ? List.of() : req).as(path).containsExactlyInAnyOrderElementsOf(required);
	}

	@Test
	void apiDocsAnswerWithBearerToken() throws Exception {
		String id = UUID.randomUUID().toString().substring(0, 8);
		String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Ana", "email": "%s@example.com", "password": "%s", "organizationName": "Org %s",
				 "organizationSlug": "org-%s"}""".formatted(id, "x1".repeat(8), id, id)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");
		mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
	}

}
