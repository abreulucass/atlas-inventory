package io.github.abreulucass.atlas.access.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizations")
public class Organization {

	@Id
	@GeneratedValue
	private UUID id;

	private String name;

	// the database also refuses updates of this column (ORG-05)
	@Column(updatable = false)
	private String slug;

	private Instant archivedAt;

	protected Organization() {
	}

	public Organization(String name, String slug) {
		this.name = name;
		this.slug = slug;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
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
