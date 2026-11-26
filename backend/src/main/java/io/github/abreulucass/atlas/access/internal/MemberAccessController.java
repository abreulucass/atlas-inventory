package io.github.abreulucass.atlas.access.internal;

import java.net.URI;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ids are plain strings: {@link MemberAccess} judges them in the spec's order. */
@RestController
@RequestMapping("/api/members/{userId}/access")
class MemberAccessController {

	private final MemberAccess access;

	MemberAccessController(MemberAccess access) {
		this.access = access;
	}

	record Grant(@Nullable String companyId, @Nullable String warehouseId) {
	}

	@GetMapping
	List<GrantView> list(@PathVariable String userId) {
		return access.list(userId);
	}

	@PostMapping
	ResponseEntity<GrantView> grant(@PathVariable String userId, @RequestBody Grant request) {
		GrantView grant = access.grant(userId, request.companyId(), request.warehouseId());
		String target = grant.warehouseId() == null ? "companies/" + grant.companyId()
				: "warehouses/" + grant.warehouseId();
		return ResponseEntity.created(URI.create("/api/members/" + userId + "/access/" + target)).body(grant);
	}

}
