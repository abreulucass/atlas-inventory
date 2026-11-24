package io.github.abreulucass.atlas.company.internal;

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
@Table(name = "companies")
public class Company {

	@Id
	@GeneratedValue
	private UUID id;

	// filled and filtered by Hibernate from the tenant context, never set by callers (ADR-002)
	@TenantId
	private UUID tenantId;

	private String name;

	@Column(updatable = false)
	private @Nullable String cnpj;

	private @Nullable Instant archivedAt;

	protected Company() {
	}

	public Company(String name, @Nullable String cnpj) {
		this.name = name;
		this.cnpj = cnpj;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public @Nullable String getCnpj() {
		return cnpj;
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
