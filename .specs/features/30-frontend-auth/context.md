# Frontend Skeleton and Authentication Context

**Gathered:** 2026-11-26
**Spec:** `.specs/features/30-frontend-auth/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Create `frontend/` (Next.js + shadcn/ui) with a BFF on httpOnly cookies, registration, login, logout and an authenticated shell. Domain screens and organization switching stay out.

---

## Implementation Decisions

### Scope

- Skeleton + auth in this feature; domain screens in following issues. User chose "Esqueleto + auth (Recommended)".

### UI language

- Fixed pt-BR, no i18n library. User chose "pt-BR fixo (Recommended)".

### Look

- Default shadcn, neutral theme, sidebar + header, light/dark; no captures. User chose "shadcn padrão (Recommended)".

### Tests

- Vitest for logic and the BFF; Playwright E2E against the real backend (Compose) in CI, in a separate job. User chose "Vitest + Playwright (Recommended)".

### Organization switching

- Out of this feature; no endpoint lists the user's organizations. Selector + `GET /api/me/organizations` become their own issue. User chose "Fora desta feature (Recommended)".

### Slug on registration

- Derived from the organization name until the user edits it; editable; client validation mirrors the backend. User chose "Sugerido do nome, editável (Recommended)".

### Agent's Discretion

None delegated.

### Declined / Undiscussed Gray Areas → Assumptions

pt-BR routes (`/entrar`, `/cadastro`), npm/Node 24/Next 16, server-only `ATLAS_API_URL`, fixed pt-BR error texts - logged as assumptions in the spec for the user to correct.

---

## Specific References

No specific requirements - open to standard approaches (default shadcn).

---

## Deferred Ideas

- Organization switching (selector + listing endpoint in the backend).
- Domain screens: companies/warehouses, catalog, stock, transfers, members.
