package io.github.abreulucass.atlas.access.internal;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.CurrentUser;
import io.github.abreulucass.atlas.access.NotAllowedException;
import io.github.abreulucass.atlas.access.Organizations;
import io.github.abreulucass.atlas.access.TenantContext;

/**
 * Members of the tenant context's organization. Every write takes the organization's member lock first and judges the
 * rules after it, so a racing request sees the state the winner committed.
 */
@Service
class Members {

	private final MembershipRepository memberships;

	private final UserRepository users;

	private final Organizations organizations;

	private final JdbcClient jdbc;

	Members(MembershipRepository memberships, UserRepository users, Organizations organizations, JdbcClient jdbc) {
		this.memberships = memberships;
		this.users = users;
		this.organizations = organizations;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	List<MemberView> list() {
		return memberships.findMembers(TenantContext.require());
	}

	@Transactional
	MemberView add(String email, String role) {
		UUID organizationId = TenantContext.require();
		Role callerRole = lockAndRequireManager(organizationId);
		organizations.requireWritable(organizationId);
		String normalized = AuthService.normalizeEmail(email);
		Role granted = parseRole(role);
		User user = users.findByEmail(normalized).orElseThrow(UserNotFoundException::new);
		if (callerRole == Role.ADMIN && granted != Role.MEMBER) {
			throw new NotAllowedException();
		}
		try {
			memberships.saveAndFlush(new Membership(user.getId(), organizationId, granted));
		}
		catch (DataIntegrityViolationException e) {
			if (NestedExceptionUtils.getMostSpecificCause(e)
				.getMessage()
				.contains("memberships_user_id_organization_id_key")) {
				throw new AlreadyMemberException();
			}
			throw e;
		}
		return new MemberView(user.getId(), user.getName(), user.getEmail(), granted);
	}

	/** Takes the transaction-scoped member lock of the organization, then returns the caller's role. */
	private Role lockAndRequireManager(UUID organizationId) {
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?, 0))")
			.param("members:" + organizationId)
			.query((rs, i) -> 1)
			.single();
		Role callerRole = memberships.findByUserIdAndOrganizationId(CurrentUser.id(), organizationId)
			.map(Membership::getRole)
			.orElse(Role.MEMBER);
		if (callerRole == Role.MEMBER) {
			throw new NotAllowedException();
		}
		return callerRole;
	}

	private static Role parseRole(String role) {
		return Arrays.stream(Role.values())
			.filter(r -> r.name().equals(role))
			.findFirst()
			.orElseThrow(InvalidRoleException::new);
	}

}
