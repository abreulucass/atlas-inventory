# Authentication Context

**Gathered:** 2026-11-24
**Spec:** `.specs/features/12-authentication/spec.md`
**Status:** Ready for design

## Feature Boundary

Self-hosted users, sign-up, login, refresh, logout and organization switch in `access`, with each authenticated request running in `TenantContext`.

## Implementation Decisions

User answer to all four: "segue as recomendações".

### Identity
- Self-hosted e-mail + password; JWT signed by the backend (Spring Security). No external OIDC provider.

### Session
- Access JWT ~15 min with the active organization; opaque refresh token in the DB, rotated on each use. Switching organization issues new tokens.

### Entry
- Public sign-up creates organization + `OWNER` membership. Invitations are a separate feature.

### Roles
- One fixed role per membership (`OWNER`, `ADMIN`, `MEMBER`); no per-company/warehouse permissions yet.

### Out of scope
- Password reset, e-mail verification, MFA, social login, login rate limiting, frontend.

### Agent's Discretion
- Defaults marked `n` in the spec's Assumptions table, for the user to correct.

## Specific References

No specific requirements - open to standard approaches.

## Deferred Ideas

- Invitations; password reset; e-mail verification; login rate limiting; per-company/warehouse permissions.
