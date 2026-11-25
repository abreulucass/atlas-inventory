package io.github.abreulucass.atlas.access.internal;

import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.access.CurrentUser;
import io.github.abreulucass.atlas.access.TenantContext;

@RestController
class MeController {

	record Me(UUID id, String name, String email, UUID organizationId, Role role) {
	}

	private final UserRepository users;

	private final MembershipRepository memberships;

	MeController(UserRepository users, MembershipRepository memberships) {
		this.users = users;
		this.memberships = memberships;
	}

	@GetMapping("/api/me")
	@Transactional(readOnly = true)
	Me me() {
		UUID userId = CurrentUser.id();
		UUID organizationId = TenantContext.require();
		User user = users.findById(userId).orElseThrow();
		Membership membership = memberships.findByUserIdAndOrganizationId(userId, organizationId).orElseThrow();
		return new Me(user.getId(), user.getName(), user.getEmail(), organizationId, membership.getRole());
	}

}
