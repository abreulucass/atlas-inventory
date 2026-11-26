package io.github.abreulucass.atlas.access.internal;

import java.util.List;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	private static final String SCHEME = "bearerAuth";

	private static final List<String> PUBLIC_PATHS = List.of("/api/auth/register", "/api/auth/login",
			"/api/auth/refresh", "/api/auth/logout");

	@Bean
	OpenAPI atlasOpenApi() {
		return new OpenAPI().info(new Info().title("Atlas Inventory API").version("0.1.0"))
			.components(new io.swagger.v3.oas.models.Components().addSecuritySchemes(SCHEME,
					new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
			.addSecurityItem(new SecurityRequirement().addList(SCHEME));
	}

	/** The four public auth operations opt out of the global requirement with an empty list. */
	@Bean
	OpenApiCustomizer publicAuthOperations() {
		return openApi -> PUBLIC_PATHS.forEach(path -> {
			var item = openApi.getPaths() == null ? null : openApi.getPaths().get(path);
			if (item != null && item.getPost() != null) {
				item.getPost().setSecurity(List.of());
			}
		});
	}

}
