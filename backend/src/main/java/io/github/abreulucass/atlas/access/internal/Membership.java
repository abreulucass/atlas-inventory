package io.github.abreulucass.atlas.access.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "memberships")
public class Membership {

	@Id
	@GeneratedValue
	private UUID id;

	private UUID userId;

	private UUID organizationId;

	@Enumerated(EnumType.STRING)
	private Role role;

	@Column(updatable = false)
	private Instant createdAt = Instant.now();

	protected Membership() {
	}

	public Membership(UUID userId, UUID organizationId, Role role) {
		this.userId = userId;
		this.organizationId = organizationId;
		this.role = role;
	}

	public UUID getUserId() {
		return userId;
	}

	public UUID getOrganizationId() {
		return organizationId;
	}

	public Role getRole() {
		return role;
	}

	void changeRole(Role role) {
		this.role = role;
	}

}
