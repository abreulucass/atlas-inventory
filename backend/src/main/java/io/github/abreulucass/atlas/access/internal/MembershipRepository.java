package io.github.abreulucass.atlas.access.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

	Optional<Membership> findFirstByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

	Optional<Membership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

	long countByOrganizationIdAndRole(UUID organizationId, Role role);

	@Query("""
			select new io.github.abreulucass.atlas.access.internal.MemberView(u.id, u.name, u.email, m.role)
			from Membership m, User u where u.id = m.userId and m.organizationId = :organizationId
			order by u.name, u.email""")
	List<MemberView> findMembers(UUID organizationId);

}
