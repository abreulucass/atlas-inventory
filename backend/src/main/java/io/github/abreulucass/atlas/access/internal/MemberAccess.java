package io.github.abreulucass.atlas.access.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.CurrentUser;
import io.github.abreulucass.atlas.access.GrantTargets;
import io.github.abreulucass.atlas.access.NotAllowedException;
import io.github.abreulucass.atlas.access.Organizations;
import io.github.abreulucass.atlas.access.TenantContext;

/**
 * Companies and warehouses granted to members of the tenant context's organization. Writes take the member lock of
 * {@link Members} first, so a grant never outlives a removal it races.
 */
@Service
class MemberAccess {

	private final Members members;

	private final MembershipRepository memberships;

	private final Organizations organizations;

	private final GrantTargets targets;

	private final JdbcClient jdbc;

	MemberAccess(Members members, MembershipRepository memberships, Organizations organizations, GrantTargets targets,
			JdbcClient jdbc) {
		this.members = members;
		this.memberships = memberships;
		this.organizations = organizations;
		this.targets = targets;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	List<GrantView> list(String id) {
		UUID organizationId = TenantContext.require();
		boolean manager = memberships.findByUserIdAndOrganizationId(CurrentUser.id(), organizationId)
			.map(m -> m.getRole() != Role.MEMBER)
			.orElse(false);
		// a MEMBER is refused before the lookup, so another member's existence never leaks
		if (!manager && !id.equals(CurrentUser.id().toString())) {
			throw new NotAllowedException();
		}
		UUID userId = requireMember(id, organizationId);
		return jdbc.sql("""
				select company_id, warehouse_id from member_access where user_id = ?
				order by warehouse_id is not null, id""")
			.param(userId)
			.query((rs, i) -> view(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
			.list();
	}

	@Transactional
	GrantView grant(String id, @Nullable String companyId, @Nullable String warehouseId) {
		UUID organizationId = TenantContext.require();
		members.lockAndRequireManager(organizationId);
		organizations.requireWritable(organizationId);
		UUID userId = requireMember(id, organizationId);
		if ((companyId == null) == (warehouseId == null)) {
			throw new InvalidGrantException();
		}
		UUID warehouse = warehouseId == null ? null : parseBody(warehouseId);
		UUID company;
		if (warehouse == null) {
			company = parseBody(companyId);
			if (!targets.companyExists(company)) {
				throw new GrantTargetNotFoundException();
			}
		}
		else {
			company = targets.companyOfWarehouse(warehouse).orElseThrow(GrantTargetNotFoundException::new);
		}
		try {
			jdbc.sql("""
					insert into member_access (id, tenant_id, user_id, company_id, warehouse_id, created_at)
					values (?, ?, ?, ?, ?, now())""")
				.params(UUID.randomUUID(), organizationId, userId, company, warehouse)
				.update();
		}
		catch (DataIntegrityViolationException e) {
			String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
			if (message.contains("member_access_company_key") || message.contains("member_access_warehouse_key")) {
				throw new DuplicateGrantException();
			}
			throw e;
		}
		return view(company, warehouse);
	}

	private UUID requireMember(String id, UUID organizationId) {
		UUID userId = Members.parseUserId(id);
		memberships.findByUserIdAndOrganizationId(userId, organizationId).orElseThrow(MemberNotFoundException::new);
		return userId;
	}

	private static UUID parseBody(String id) {
		try {
			return UUID.fromString(id);
		}
		catch (IllegalArgumentException e) {
			throw new InvalidGrantException();
		}
	}

	private static GrantView view(UUID company, @Nullable UUID warehouse) {
		return new GrantView(warehouse == null ? GrantType.COMPANY : GrantType.WAREHOUSE, company, warehouse);
	}

}
