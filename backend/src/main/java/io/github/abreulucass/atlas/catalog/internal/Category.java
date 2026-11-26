package io.github.abreulucass.atlas.catalog.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "categories")
public class Category {

	@Id
	@GeneratedValue
	private UUID id;

	// filled and filtered by Hibernate from the tenant context, never set by callers (ADR-002)
	@TenantId
	private UUID tenantId;

	private String name;

	private @Nullable Instant archivedAt;

	protected Category() {
	}

	public Category(String name) {
		this.name = name;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public boolean isArchived() {
		return archivedAt != null;
	}

	public void archive() {
		if (archivedAt == null) {
			archivedAt = Instant.now();
		}
	}

	public void unarchive() {
		archivedAt = null;
	}

}
