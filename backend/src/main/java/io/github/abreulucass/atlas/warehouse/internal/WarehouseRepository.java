package io.github.abreulucass.atlas.warehouse.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

	List<Warehouse> findByCompanyIdOrderByNameAsc(UUID companyId);

	List<Warehouse> findByCompanyIdAndArchivedAtIsNullOrderByNameAsc(UUID companyId);

	Optional<Warehouse> findByIdAndCompanyId(UUID id, UUID companyId);

}
