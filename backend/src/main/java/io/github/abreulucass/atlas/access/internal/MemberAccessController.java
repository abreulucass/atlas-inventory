package io.github.abreulucass.atlas.access.internal;

import java.net.URI;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

/** Ids and the grant body stay raw: {@link MemberAccess} judges them in the spec's order. */
@RestController
@RequestMapping("/api/members/{userId}/access")
class MemberAccessController {

	private final MemberAccess access;

	MemberAccessController(MemberAccess access) {
		this.access = access;
	}

	@GetMapping
	List<GrantView> list(@PathVariable String userId) {
		return access.list(userId);
	}

	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = GrantBody.class)))
	@PostMapping
	ResponseEntity<GrantView> grant(@PathVariable String userId, @RequestBody(required = false) @Nullable String body) {
		// raw body: decoding it is the last judgement of the grant, after 403, 409 and 404
		GrantView grant = access.grant(userId, body);
		String target = grant.warehouseId() == null ? "companies/" + grant.companyId()
				: "warehouses/" + grant.warehouseId();
		return ResponseEntity.created(URI.create("/api/members/" + userId + "/access/" + target)).body(grant);
	}

	@DeleteMapping("/companies/{companyId}")
	ResponseEntity<Void> revokeCompany(@PathVariable String userId, @PathVariable String companyId) {
		access.revokeCompany(userId, companyId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/warehouses/{warehouseId}")
	ResponseEntity<Void> revokeWarehouse(@PathVariable String userId, @PathVariable String warehouseId) {
		access.revokeWarehouse(userId, warehouseId);
		return ResponseEntity.noContent().build();
	}

}
