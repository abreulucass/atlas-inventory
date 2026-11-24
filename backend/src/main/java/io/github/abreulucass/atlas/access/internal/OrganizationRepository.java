package io.github.abreulucass.atlas.access.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

	// "for share": serializes with the update that archives the organization
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select o from Organization o where o.id = :id")
	Optional<Organization> findForShare(UUID id);

}
