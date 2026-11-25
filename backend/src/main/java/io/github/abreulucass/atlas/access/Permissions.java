package io.github.abreulucass.atlas.access;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.abreulucass.atlas.access.internal.MembershipRepository;
import io.github.abreulucass.atlas.access.internal.Role;

/** Role rules of the current user in the organization of the tenant context. */
@Service
public class Permissions {

	private final MembershipRepository memberships;

	Permissions(MembershipRepository memberships) {
		this.memberships = memberships;
	}

	/** Passes for OWNER and ADMIN; the role is read from the database, never from the token. */
	public void requireManager() {
		UUID tenant = TenantContext.require();
		boolean manager = memberships.findByUserIdAndOrganizationId(CurrentUser.id(), tenant)
			.map(m -> m.getRole() != Role.MEMBER)
			.orElse(false);
		if (!manager) {
			throw new NotAllowedException();
		}
	}

}
