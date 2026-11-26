package io.github.abreulucass.atlas.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.abreulucass.atlas.access.internal.MembershipRepository;
import io.github.abreulucass.atlas.access.internal.Role;

/** Role rules of the current user in the organization of the tenant context. */
@Service
public class Permissions {

	private final MembershipRepository memberships;

	private final JdbcClient jdbc;

	Permissions(MembershipRepository memberships, JdbcClient jdbc) {
		this.memberships = memberships;
		this.jdbc = jdbc;
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

	/**
	 * OWNER and ADMIN see everything; a MEMBER sees their grants. Role and grants are read from the database on every
	 * call, so a revoke or a role change applies on the next request. Call inside a tenant transaction.
	 */
	public Visibility visibility() {
		UUID tenant = TenantContext.require();
		// fails closed: CurrentUser.id() throws without an authenticated user
		UUID user = CurrentUser.id();
		boolean member = memberships.findByUserIdAndOrganizationId(user, tenant)
			.map(m -> m.getRole() == Role.MEMBER)
			.orElse(true);
		if (!member) {
			return Visibility.ALL;
		}
		Set<UUID> companies = new HashSet<>();
		Set<UUID> warehouses = new HashSet<>();
		Set<UUID> warehouseCompanies = new HashSet<>();
		jdbc.sql("select company_id, warehouse_id from member_access where user_id = ?").param(user).query(rs -> {
			UUID warehouse = rs.getObject(2, UUID.class);
			if (warehouse == null) {
				companies.add(rs.getObject(1, UUID.class));
			}
			else {
				warehouses.add(warehouse);
				warehouseCompanies.add(rs.getObject(1, UUID.class));
			}
		});
		return new Visibility(false, companies, warehouses, warehouseCompanies);
	}

}
