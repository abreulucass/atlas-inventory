package io.github.abreulucass.atlas.access.internal;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	private static final int MIN_SECRET_BYTES = 32;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationEntryPoint entryPoint) {
		http.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(a -> a
				// an error response is already decided; the original request was authorized before it
				.dispatcherTypeMatchers(DispatcherType.ERROR)
				.permitAll()
				.requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
						"/actuator/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**",
						"/swagger-ui.html")
				.permitAll()
				.anyRequest()
				.authenticated())
			.addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class)
			.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
			.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint));
		return http.build();
	}

	@Bean
	AuthenticationEntryPoint unauthorizedEntryPoint(JsonMapper mapper) {
		return (request, response, exception) -> {
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
					"Authentication is required"));
		};
	}

	@Bean
	SecretKey jwtKey(@Value("${atlas.security.jwt-secret}") String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("atlas.security.jwt-secret must have at least " + MIN_SECRET_BYTES + " bytes");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtKey, MembershipValidator membershipValidator) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(
				new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), membershipValidator));
		return decoder;
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
	}

}
