package io.github.abreulucass.atlas.catalog.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

import io.github.abreulucass.atlas.catalog.Unit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "products")
public class Product {

	@Id
	@GeneratedValue
	private UUID id;

	// filled and filtered by Hibernate from the tenant context, never set by callers (ADR-002)
	@TenantId
	private UUID tenantId;

	@Column(updatable = false)
	private String sku;

	private String name;

	@Column(updatable = false)
	@Enumerated(EnumType.STRING)
	private Unit unit;

	private @Nullable UUID categoryId;

	private @Nullable Instant archivedAt;

	@Version
	private long version;

	protected Product() {
	}

	public Product(String sku, String name, Unit unit, @Nullable UUID categoryId) {
		this.sku = sku;
		this.name = name;
		this.unit = unit;
		this.categoryId = categoryId;
	}

	public UUID getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public String getName() {
		return name;
	}

	public Unit getUnit() {
		return unit;
	}

	public @Nullable UUID getCategoryId() {
		return categoryId;
	}

	public long getVersion() {
		return version;
	}

	public boolean isArchived() {
		return archivedAt != null;
	}

	public void rename(String name) {
		this.name = name;
	}

	public void moveTo(@Nullable UUID categoryId) {
		this.categoryId = categoryId;
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
