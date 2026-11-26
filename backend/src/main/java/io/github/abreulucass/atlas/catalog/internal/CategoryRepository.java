package io.github.abreulucass.atlas.catalog.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

	@Query("select c from Category c order by lower(c.name), c.id")
	List<Category> findAllOrdered();

	@Query("select c from Category c where c.archivedAt is null order by lower(c.name), c.id")
	List<Category> findActiveOrdered();

}
