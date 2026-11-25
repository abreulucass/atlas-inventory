# Organization Members Context

**Gathered:** 2026-11-25
**Spec:** `.specs/features/18-members/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Managers add registered users to the organization by e-mail, change their role and remove them; every member lists the members. Per-company / per-warehouse access is the next feature.

---

## Implementation Decisions

### Split

- Two features: members first, per-company / per-warehouse scope after. User: "Duas features (Recommended)".

### Joining

- `OWNER`/`ADMIN` adds an existing user by e-mail; no invitation, no e-mail delivery. User: "Adicionar por e-mail (Recommended)".

### Hierarchy

- `OWNER` manages anyone and grants any role; `ADMIN` manages only `MEMBER`s and grants only `MEMBER`; at least one `OWNER` remains. User: "OWNER tudo; ADMIN só MEMBER (Recommended)".

### Removal

- Revoke refresh tokens and check the membership on every authenticated request, so access ends at once. User: "Recusar na hora (Recommended)".

### Listing and leaving

- Any member lists; no leave endpoint. User: "Todos listam; sem saída (Recommended)".

### Agent's Discretion

None delegated; the remaining defaults are in the spec's Assumptions table with `n`.

### Declined / Undiscussed Gray Areas → Assumptions

Endpoint shape, response body, precedence, self-removal: logged in the spec's Assumptions table.

---

## Specific References

No specific requirements - follows features 14 and 16.

---

## Deferred Ideas

- Invitations with token / e-mail.
- Leaving an organization.
- Registering without creating an organization.
