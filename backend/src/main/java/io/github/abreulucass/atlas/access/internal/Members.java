package io.github.abreulucass.atlas.access.internal;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.TenantContext;

/** Members of the tenant context's organization. */
@Service
class Members {

	private final MembershipRepository memberships;

	Members(MembershipRepository memberships) {
		this.memberships = memberships;
	}

	@Transactional(readOnly = true)
	List<MemberView> list() {
		return memberships.findMembers(TenantContext.require());
	}

}
