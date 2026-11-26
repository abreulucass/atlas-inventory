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

	private final RefreshTokens refreshTokens;

	Members(MembershipRepository memberships, UserRepository users, Organizations organizations, JdbcClient jdbc,
			RefreshTokens refreshTokens) {
		this.memberships = memberships;
		this.users = users;
		this.organizations = organizations;
		this.jdbc = jdbc;
		this.refreshTokens = refreshTokens;
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

	@Transactional
	MemberView changeRole(String id, String role) {
		UUID organizationId = TenantContext.require();
		Role callerRole = lockAndRequireManager(organizationId);
		organizations.requireWritable(organizationId);
		UUID userId = parseUserId(id);
		Membership target = memberships.findByUserIdAndOrganizationId(userId, organizationId)
			.orElseThrow(MemberNotFoundException::new);
		Role granted = parseRole(role);
		if (callerRole == Role.ADMIN && (target.getRole() != Role.MEMBER || granted != Role.MEMBER)) {
			throw new NotAllowedException();
		}
		if (target.getRole() == Role.OWNER && granted != Role.OWNER
				&& memberships.countByOrganizationIdAndRole(organizationId, Role.OWNER) == 1) {
			throw new LastOwnerException();
		}
		target.changeRole(granted);
		User user = users.findById(userId).orElseThrow();
		return new MemberView(userId, user.getName(), user.getEmail(), granted);
	}

	// No last-OWNER check: only an OWNER removes an OWNER and never themselves, so another OWNER always remains.
	@Transactional
	void remove(String id) {
		UUID organizationId = TenantContext.require();
		Role callerRole = lockAndRequireManager(organizationId);
		organizations.requireWritable(organizationId);
		UUID userId = parseUserId(id);
		Membership target = memberships.findByUserIdAndOrganizationId(userId, organizationId)
			.orElseThrow(MemberNotFoundException::new);
		if (userId.equals(CurrentUser.id()) || (callerRole == Role.ADMIN && target.getRole() != Role.MEMBER)) {
			throw new NotAllowedException();
		}
		// Lock order everywhere: membership row first, tokens after. The delete waits for every token issue holding
		// the row FOR SHARE, so the revoke then sees the tokens they inserted, and later issues find no row.
		memberships.delete(target);
		memberships.flush();
		refreshTokens.revokeAll(userId, organizationId);
	}

	/** Takes the transaction-scoped member lock of the organization, then returns the caller's role. */
	Role lockAndRequireManager(UUID organizationId) {
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

	// parsed after the caller and archived checks, so a malformed id follows the spec's precedence
	static UUID parseUserId(String id) {
		UUID userId = Uuids.parse(id);
		if (userId == null) {
			throw new MemberNotFoundException();
		}
		return userId;
	}

	private static Role parseRole(String role) {
		return Arrays.stream(Role.values())
			.filter(r -> r.name().equals(role))
			.findFirst()
			.orElseThrow(InvalidRoleException::new);
	}

}
