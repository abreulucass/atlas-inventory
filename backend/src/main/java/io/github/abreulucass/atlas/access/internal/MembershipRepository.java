package io.github.abreulucass.atlas.access.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

	Optional<Membership> findFirstByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

	Optional<Membership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

}
