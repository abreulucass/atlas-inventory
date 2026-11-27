# Organization Switching Specification

**Approved**: 2026-11-27 - user: "pode seguir"

## Problem Statement

A user can belong to several organizations, and the backend already switches the active one (`POST /api/auth/switch-organization`), but nothing lists the user's organizations and the frontend has no way to switch. A member of two organizations is stuck in whichever one login picked (the oldest membership).

## Goals

- [ ] A member of several organizations sees the active one in the header and switches to another in two clicks, landing on `/` inside the new organization.
- [ ] No token reaches page JavaScript during the switch (same BFF rule as feature 30).

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Creating an organization from the selector | Not requested; registration is the only creation path |
| Invitations / adding members from the selector | Members screen is its own issue |
| Archiving / unarchiving an organization from the frontend | Domain screen, own issue |
| Remembering the last organization at login | Login keeps picking the oldest membership (feature 12) |
| Blocking the switch into an archived organization | User chose to show archived ones as selectable; writes there are already refused by the domain |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Archived organizations | Listed with `status: "ARCHIVED"`; the selector shows them with the badge "Arquivada" and lets the user switch | User chose "Mostrar com selo \"Arquivada\" (Recommended)" | y |
| Single organization | Header shows only the organization name, no menu | User chose "Só o nome, sem menu (Recommended)" | y |
| After switching | Redirect to `/` | User chose "Início \"/\" (Recommended)"; pages with ids from the old tenant would 404 | y |
| Issue | GitHub issue #32 created by the agent | User chose "Sim, crie a issue (Recommended)" | y |
| Response shape | `200` with a JSON array of `{ id, name, slug, role, status }`, no wrapper, no pagination | A user belongs to few organizations; same flat style as `GET /api/members` | n |
| Order | By `name`, then `slug` (unique, breaks ties) | Stable and readable; same style as members by name | n |
| Which one is active | The frontend compares each `id` with `organizationId` from `GET /api/me`; the endpoint carries no "current" flag | `/api/me` already returns it; no duplicate field | n |
| Module | Endpoint lives in `access` (`MeController`), reading the global `memberships` and `organizations` tables | Both are global, no RLS (ADR-002); no new module dependency | n |
| Choosing the active organization | No request; the menu just closes | Nothing to switch | n |
| Switch error placement | A pt-BR message with `role="alert"` next to the selector in the header | The menu closes on selection; the message must stay visible | n |
| List failure | `GET /api/me/organizations` failing behaves like `GET /api/me` failing today: `401` → `/auth/expired`, other errors → the app error boundary | Same rule as the existing shell data | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: List my organizations (backend) ⭐ MVP

**User Story**: As an authenticated user, I want the API to list the organizations I belong to so that a client can offer a switch.

**Why P1**: The selector has nothing to show without it.

**Acceptance Criteria**:

1. WHEN an authenticated user calls `GET /api/me/organizations` THEN the system SHALL respond `200` with one item per membership of that user, each with the organization's `id`, `name`, `slug`, the user's `role` in it and `status` (`ACTIVE` or `ARCHIVED`).
2. The system SHALL exclude from `GET /api/me/organizations` every organization in which the authenticated user has no membership, including those of other users.
3. WHEN the user belongs to several organizations THEN the system SHALL order the items by `name`, then by `slug`.
4. WHEN an organization the user belongs to is archived THEN the system SHALL list it with `status` `ARCHIVED`.
5. IF `GET /api/me/organizations` is called without a valid `Bearer` token THEN the system SHALL respond `401`.
6. The system SHALL return the same list whichever organization is active in the token.
7. The system SHALL document `GET /api/me/organizations` in the OpenAPI spec and in the README endpoint table as requiring `Bearer`.

**Independent Test**: Integration test with a user in two organizations (one archived) plus a third organization they are not in.

---

### P1: Switch organization from the header ⭐ MVP

**User Story**: As a member of several organizations, I want a selector in the header so that I can work in another organization without logging out.

**Why P1**: It is the feature.

**Acceptance Criteria**:

1. The system SHALL show the active organization's name in the header of every authenticated page.
2. WHILE the user belongs to exactly one organization the system SHALL show its name in the header without a menu trigger.
3. WHILE the user belongs to two or more organizations, WHEN the user opens the organization selector THEN the system SHALL list every organization from `GET /api/me/organizations` by name, with the active one marked.
4. WHEN an organization in the selector has `status` `ARCHIVED` THEN the system SHALL show the badge "Arquivada" next to its name.
5. WHEN the user selects an organization other than the active one THEN the system SHALL call `POST /api/auth/switch-organization` from the server, replace the access and refresh cookies with the returned tokens and redirect to `/`.
6. WHEN the switch completes THEN the system SHALL show the new organization's name and the user's role in it in the header.
7. The system SHALL never place a token in a Server Action response, a response body or header sent to the browser, or the client bundle.
8. WHILE a switch is in flight the system SHALL disable the selector's items.
9. WHEN the user selects the active organization THEN the system SHALL send no switch request.

**Independent Test**: E2E: a user in two organizations switches and sees the other organization's name and role; the old access cookie no longer appears.

---

## Edge Cases

1. IF the switch returns `403` (membership removed meanwhile) THEN the system SHALL keep the current session cookies and show "Você não faz mais parte desta organização." next to the selector.
2. IF the backend is unavailable during the switch THEN the system SHALL keep the current session cookies and show "Serviço indisponível. Tente novamente em instantes." next to the selector.
3. IF the switch returns `401` THEN the system SHALL send the user through `/auth/expired` as the rest of the shell does.
4. WHEN the header renders at 375 px wide THEN the system SHALL truncate a long organization name so the page has no horizontal scroll.
5. WHEN the header renders at 1280 px wide THEN the system SHALL show the selector, the theme toggle and the user menu on one line.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| ORGS-01 | P1 List: AC1 | Tasks | Pending |
| ORGS-02 | P1 List: AC2 | Tasks | Pending |
| ORGS-03 | P1 List: AC3 | Tasks | Pending |
| ORGS-04 | P1 List: AC4 | Tasks | Pending |
| ORGS-05 | P1 List: AC5 | Tasks | Pending |
| ORGS-06 | P1 List: AC6 | Tasks | Pending |
| ORGS-07 | P1 List: AC7 | Tasks | Pending |
| SWITCH-01 | P1 Switch: AC1 | Tasks | Pending |
| SWITCH-02 | P1 Switch: AC2 | Tasks | Pending |
| SWITCH-03 | P1 Switch: AC3 | Tasks | Pending |
| SWITCH-04 | P1 Switch: AC4 | Tasks | Pending |
| SWITCH-05 | P1 Switch: AC5 | Tasks | Pending |
| SWITCH-06 | P1 Switch: AC6 | Tasks | Pending |
| SWITCH-07 | P1 Switch: AC7 | Tasks | Pending |
| SWITCH-08 | P1 Switch: AC8 | Tasks | Pending |
| SWITCH-09 | P1 Switch: AC9 | Tasks | Pending |
| EDGE-01 | Edge 1 | Tasks | Pending |
| EDGE-02 | Edge 2 | Tasks | Pending |
| EDGE-03 | Edge 3 | Tasks | Pending |
| EDGE-04 | Edge 4 | Tasks | Pending |
| EDGE-05 | Edge 5 | Tasks | Pending |

**Coverage:** 21 total, 0 mapped to tasks, 21 unmapped ⚠️ (mapped in tasks.md)

## Sources

- GitHub issue #32 - scope and acceptance summary
- `.specs/features/30-frontend-auth/spec.md` - Out of Scope row that deferred this feature; BFF and session rules reused
- `POST /api/auth/switch-organization` (feature 12, AUTH spec) - existing switch, `403` when not a member

## Success Criteria

- [ ] A user in two organizations switches from the header and lands on `/` in the other one, in an E2E run against the real backend.
- [ ] `./mvnw verify` and the frontend lint/typecheck/test/build/check:bundle/E2E pass.
