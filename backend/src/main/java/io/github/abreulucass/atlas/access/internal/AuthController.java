package io.github.abreulucass.atlas.access.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.access.CurrentUser;
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

	record RefreshRequest(@NotNull String refreshToken) {
	}

	record SwitchRequest(@NotNull UUID organizationId) {
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

	@PostMapping("/refresh")
	TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
		return service.refresh(request.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(@Valid @RequestBody RefreshRequest request) {
		service.logout(request.refreshToken());
	}

	@PostMapping("/switch-organization")
	TokenPair switchOrganization(@Valid @RequestBody SwitchRequest request) {
		return service.switchOrganization(CurrentUser.id(), request.organizationId());
	}

}
