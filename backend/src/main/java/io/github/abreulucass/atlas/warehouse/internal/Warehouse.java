package io.github.abreulucass.atlas.warehouse.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "warehouses")
public class Warehouse {

	@Id
	@GeneratedValue
	private UUID id;

	// filled and filtered by Hibernate from the tenant context, never set by callers (ADR-002)
	@TenantId
	private UUID tenantId;

	@Column(updatable = false)
	private UUID companyId;

	private String name;

	@Column(updatable = false)
	private String code;

	private @Nullable Instant archivedAt;

	protected Warehouse() {
	}

	public Warehouse(UUID companyId, String name, String code) {
		this.companyId = companyId;
		this.name = name;
		this.code = code;
	}

	public UUID getId() {
		return id;
	}

	public UUID getCompanyId() {
		return companyId;
	}

	public String getName() {
		return name;
	}

	public String getCode() {
		return code;
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
