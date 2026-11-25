package io.github.abreulucass.atlas.access.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/auth")
class AuthController {

	record RegisterRequest(@NotNull String name, @NotNull String email, @NotNull String password,
			@NotNull String organizationName, @NotNull String organizationSlug) {
	}

	record LoginRequest(@NotNull String email, @NotNull String password) {
	}

	private final AuthService service;

	AuthController(AuthService service) {
		this.service = service;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	TokenPair register(@Valid @RequestBody RegisterRequest request) {
		return service.register(request.name(), request.email(), request.password(), request.organizationName(),
				request.organizationSlug());
	}

	@PostMapping("/login")
	TokenPair login(@Valid @RequestBody LoginRequest request) {
		return service.login(request.email(), request.password());
	}

}
