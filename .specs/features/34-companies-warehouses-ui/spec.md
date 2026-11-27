# Companies and Warehouses Screens Specification

**Approved**: 2026-11-27 - user: "aprovado"

## Problem Statement

The API already manages companies and warehouses (features 14 and 16), but the frontend only has the home page: a user who signs up cannot see, create or archive a company or a warehouse without calling the API by hand. Every later screen (catalog, stock, transfers) needs companies and warehouses to exist, so these screens come first.

## Goals

- [ ] An `OWNER` or `ADMIN` creates a company and a warehouse in it from the browser, and archives and unarchives both, in an E2E run against the real backend.
- [ ] A `MEMBER` sees only the companies and warehouses of their access and no write control.
- [ ] No token reaches page JavaScript (same BFF rule as feature 30).

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Editing or deleting a company or warehouse | The API offers neither |
| Stock balances, movements, transfers on these pages | Own issues |
| Member access grants from these pages | Members screen is its own issue |
| Pagination, search and sorting controls | The API returns the whole list ordered by name; tens of rows per organization |
| Backend changes | Every endpoint needed exists |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Navigation | Sidebar item "Empresas" → `/empresas` (list); a row opens `/empresas/[id]` with the company and its warehouses | User chose "Lista + página da empresa (Recommended)" | y |
| Create form | A modal dialog submitting a Server Action; on success it closes and the list shows the new row | User chose "Dialog (modal) (Recommended)" | y |
| Archived rows | Hidden by default; a "Mostrar arquivadas" / "Mostrar arquivados" control includes them with a badge and "Desarquivar" | User chose "Ocultos, com filtro (Recommended)" | y |
| Archive confirmation | "Arquivar" opens a confirmation dialog; "Desarquivar" acts at once | User chose "Sim, dialog de confirmação (Recommended)" | y |
| Issue | GitHub issue #34 created by the agent, branch `34-feature-companies-warehouses-ui` | Same flow as #32 | y |
| Filter state | Lives in the URL (`?arquivadas=1`), read on the server and sent as `includeArchived=true` | Survives reload, testable without a browser, no client state | n |
| Write controls by role | Hidden for `MEMBER` (role from `GET /api/me`); the backend still refuses with `403` | The backend is the guard; hiding avoids buttons that always fail | n |
| Telling `409` causes apart | The Server Action maps the backend `detail` prefix (`Company name already in use`, `CNPJ already in use`, `Warehouse name already in use`, `Warehouse code already in use`, `Company is archived`, `Organization is archived`) to a pt-BR message | The API has no error code field; adding one is a backend change out of scope | n |
| CNPJ display | 14-character CNPJs shown masked as `00.000.000/0000-00`; empty CNPJ shown as "—" | The API stores it without mask | n |
| Warehouse code input | Sent as typed; the backend strips and upper-cases it, the list shows the stored value | The backend already normalizes (`WarehouseCode.parse`) | n |
| Client-side validation | Name required, 1–120 characters after trim; warehouse code 1–20 characters of letters, digits and `-` after trim (case-insensitive); CNPJ checked only by the backend | Mirrors backend rules for instant feedback; CNPJ check digits live in one place | n |
| Unknown or hidden company id | `/empresas/[id]` renders the Next.js not-found page when the API answers `404` | A `MEMBER` without access gets `404` from the API by design | n |
| Archived company page | Shows the badge "Arquivada" and "Desarquivar"; "Novo depósito" is hidden | The API refuses warehouses in an archived company (`409`) | n |
| List failure | `401` → `/auth/expired`; other errors → the app error boundary | Same rule as the existing shell data (`authedGet`) | n |
| Write failure messages | `403` → "Seu papel não permite esta operação."; backend down → "Serviço indisponível. Tente novamente em instantes."; `401` → `/auth/expired` | Same messages and session rule as feature 30/32 | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: List companies ⭐ MVP

**User Story**: As a member of an organization, I want to see its companies so that I can pick one to work with.

**Why P1**: Entry point for every company and warehouse screen.

**Acceptance Criteria**:

1. The system SHALL show a sidebar item "Empresas" on every authenticated page, linking to `/empresas`.
2. WHEN a user opens `/empresas` THEN the system SHALL list the companies from `GET /api/companies` in the API's order, each with name, CNPJ and status ("Ativa" or "Arquivada").
3. WHILE "Mostrar arquivadas" is off the system SHALL request `GET /api/companies` without `includeArchived` and list no archived company.
4. WHEN the user turns "Mostrar arquivadas" on THEN the system SHALL request `GET /api/companies?includeArchived=true` and list archived companies with the badge "Arquivada".
5. WHEN the organization has no company to list THEN the system SHALL show "Nenhuma empresa cadastrada."
6. WHEN the user selects a company in the list THEN the system SHALL open `/empresas/[id]` for that company.
7. WHILE the list loads the system SHALL show a skeleton in place of the table.

**Independent Test**: Integration (Vitest) of the page with a mocked API: active only, with archived, empty.

---

### P1: Create a company ⭐ MVP

**User Story**: As an `OWNER` or `ADMIN`, I want to create a company from the list so that I can start registering warehouses.

**Why P1**: Without it a new organization has nothing to show.

**Acceptance Criteria**:

1. WHILE the user's role is `OWNER` or `ADMIN` the system SHALL show a "Nova empresa" button on `/empresas`.
2. WHILE the user's role is `MEMBER` the system SHALL show no "Nova empresa" button.
3. WHEN the user uses "Nova empresa" THEN the system SHALL open a dialog with the fields "Nome" and "CNPJ (opcional)" and the button "Criar empresa".
4. WHEN the user submits a valid form THEN the system SHALL call `POST /api/companies` from the server with `name` and `cnpj` (omitted when blank), close the dialog and show the new company in the list.
5. IF the name is blank or longer than 120 characters after trim THEN the system SHALL show "Informe um nome de até 120 caracteres." under "Nome" and send no request.
6. IF the API answers `409` with a duplicate name THEN the system SHALL keep the dialog open and show "Já existe uma empresa com este nome." under "Nome".
7. IF the API answers `409` with a duplicate CNPJ THEN the system SHALL keep the dialog open and show "Já existe uma empresa com este CNPJ." under "CNPJ (opcional)".
8. IF the API answers `400` for an invalid CNPJ THEN the system SHALL keep the dialog open and show "CNPJ inválido." under "CNPJ (opcional)".
9. WHILE the creation request is in flight the system SHALL disable "Criar empresa".

**Independent Test**: E2E: an `OWNER` creates a company and sees it listed; Vitest for each error mapping.

---

### P1: Company page with its warehouses ⭐ MVP

**User Story**: As a member, I want a company page listing its warehouses so that I can see where stock lives.

**Why P1**: Warehouses only exist inside a company.

**Acceptance Criteria**:

1. WHEN a user opens `/empresas/[id]` THEN the system SHALL show the company's name, CNPJ and status from `GET /api/companies/{id}`.
2. WHEN a user opens `/empresas/[id]` THEN the system SHALL list the warehouses from `GET /api/companies/{id}/warehouses` in the API's order, each with code, name and status ("Ativo" or "Arquivado").
3. WHILE "Mostrar arquivados" is off the system SHALL request the warehouses without `includeArchived` and list no archived warehouse.
4. WHEN the user turns "Mostrar arquivados" on THEN the system SHALL request `includeArchived=true` and list archived warehouses with the badge "Arquivado".
5. WHEN the company has no warehouse to list THEN the system SHALL show "Nenhum depósito cadastrado."
6. IF `GET /api/companies/{id}` answers `404` THEN the system SHALL render the not-found page.
7. The system SHALL show a link back to `/empresas` on the company page.

**Independent Test**: Vitest of the page with a mocked API; E2E opens a company from the list.

---

### P1: Create a warehouse ⭐ MVP

**User Story**: As an `OWNER` or `ADMIN`, I want to create a warehouse in a company so that stock can be registered there.

**Why P1**: Stock movements need a warehouse.

**Acceptance Criteria**:

1. WHILE the user's role is `OWNER` or `ADMIN` and the company is active the system SHALL show a "Novo depósito" button on `/empresas/[id]`.
2. WHILE the user's role is `MEMBER` or the company is archived the system SHALL show no "Novo depósito" button.
3. WHEN the user uses "Novo depósito" THEN the system SHALL open a dialog with the fields "Código" and "Nome" and the button "Criar depósito".
4. WHEN the user submits a valid form THEN the system SHALL call `POST /api/companies/{id}/warehouses` from the server with `code` and `name`, close the dialog and show the new warehouse in the list.
5. IF the code is blank, longer than 20 characters or has characters other than letters, digits and `-` after trim THEN the system SHALL show "Use até 20 letras, números ou hífen." under "Código" and send no request.
6. IF the name is blank or longer than 120 characters after trim THEN the system SHALL show "Informe um nome de até 120 caracteres." under "Nome" and send no request.
7. IF the API answers `409` with a duplicate code THEN the system SHALL keep the dialog open and show "Já existe um depósito com este código nesta empresa." under "Código".
8. IF the API answers `409` with a duplicate name THEN the system SHALL keep the dialog open and show "Já existe um depósito com este nome nesta empresa." under "Nome".
9. WHILE the creation request is in flight the system SHALL disable "Criar depósito".

**Independent Test**: E2E: an `OWNER` creates a warehouse and sees it listed; Vitest for each error mapping.

---

### P1: Archive and unarchive ⭐ MVP

**User Story**: As an `OWNER` or `ADMIN`, I want to archive a company or warehouse I no longer use, and bring it back, so that lists show only what is in use.

**Why P1**: The only lifecycle action the API offers.

**Acceptance Criteria**:

1. WHILE the user's role is `OWNER` or `ADMIN` the system SHALL show "Arquivar" on an active company's page and on each active warehouse row.
2. WHILE the user's role is `OWNER` or `ADMIN` the system SHALL show "Desarquivar" on an archived company's page and on each archived warehouse row.
3. WHILE the user's role is `MEMBER` the system SHALL show neither "Arquivar" nor "Desarquivar".
4. WHEN the user uses "Arquivar" on a company THEN the system SHALL open a confirmation dialog with the title "Arquivar empresa?" and the buttons "Cancelar" and "Arquivar".
5. WHEN the user uses "Arquivar" on a warehouse THEN the system SHALL open a confirmation dialog with the title "Arquivar depósito?" and the buttons "Cancelar" and "Arquivar".
6. WHEN the user confirms with "Arquivar" THEN the system SHALL call the matching `POST .../archive` from the server, close the dialog and show the item as archived.
7. WHEN the user uses "Cancelar" in the confirmation dialog THEN the system SHALL close it and send no request.
8. WHEN the user uses "Desarquivar" THEN the system SHALL call the matching `POST .../unarchive` from the server without a confirmation dialog and show the item as active.
9. WHEN a company is archived from its page THEN the system SHALL stay on `/empresas/[id]` showing the badge "Arquivada".

**Independent Test**: E2E: archive a warehouse with confirmation, show archived, unarchive it; same for the company.

---

### P1: Session and access rules ⭐ MVP

**User Story**: As the product owner, I want these screens to follow the BFF and tenant rules so that no token leaks and each member sees only their access.

**Why P1**: Security invariant of the project.

**Acceptance Criteria**:

1. The system SHALL perform every companies and warehouses API call from the server with the access token from the httpOnly cookie.
2. The system SHALL never place a token in a Server Action response, a response body or header sent to the browser, or the client bundle.
3. WHEN a `MEMBER` opens `/empresas` THEN the system SHALL list only the companies the API returns for that member.

**Independent Test**: E2E with a `MEMBER` granted one of two companies; `check:bundle`.

---

## Edge Cases

1. IF a write returns `403` THEN the system SHALL show "Seu papel não permite esta operação." in the dialog or next to the action.
2. IF the backend is unavailable during a write THEN the system SHALL keep the session cookies and show "Serviço indisponível. Tente novamente em instantes." in the dialog or next to the action.
3. IF a write returns `401` THEN the system SHALL send the user through `/auth/expired`.
4. IF a write returns `409` because the organization is archived THEN the system SHALL show "Esta organização está arquivada." in the dialog or next to the action.
5. IF creating a warehouse returns `409` because the company is archived THEN the system SHALL show "Esta empresa está arquivada." in the dialog.
6. WHEN a page renders at 375 px wide THEN the system SHALL show no page-level horizontal scroll; a table wider than the viewport scrolls inside its own container.
7. WHEN a page renders at 1280 px wide THEN the system SHALL show every table column without horizontal scroll.
8. WHEN a company's CNPJ has 14 characters THEN the system SHALL show it as `00.000.000/0000-00`.
9. WHEN a company has no CNPJ THEN the system SHALL show "—" in its place.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| COLIST-01 | P1 List companies: AC1 | Tasks | Pending |
| COLIST-02 | P1 List companies: AC2 | Tasks | Pending |
| COLIST-03 | P1 List companies: AC3 | Tasks | Pending |
| COLIST-04 | P1 List companies: AC4 | Tasks | Pending |
| COLIST-05 | P1 List companies: AC5 | Tasks | Pending |
| COLIST-06 | P1 List companies: AC6 | Tasks | Pending |
| COLIST-07 | P1 List companies: AC7 | Tasks | Pending |
| CONEW-01 | P1 Create company: AC1 | Tasks | Pending |
| CONEW-02 | P1 Create company: AC2 | Tasks | Pending |
| CONEW-03 | P1 Create company: AC3 | Tasks | Pending |
| CONEW-04 | P1 Create company: AC4 | Tasks | Pending |
| CONEW-05 | P1 Create company: AC5 | Tasks | Pending |
| CONEW-06 | P1 Create company: AC6 | Tasks | Pending |
| CONEW-07 | P1 Create company: AC7 | Tasks | Pending |
| CONEW-08 | P1 Create company: AC8 | Tasks | Pending |
| CONEW-09 | P1 Create company: AC9 | Tasks | Pending |
| COPAGE-01 | P1 Company page: AC1 | Tasks | Pending |
| COPAGE-02 | P1 Company page: AC2 | Tasks | Pending |
| COPAGE-03 | P1 Company page: AC3 | Tasks | Pending |
| COPAGE-04 | P1 Company page: AC4 | Tasks | Pending |
| COPAGE-05 | P1 Company page: AC5 | Tasks | Pending |
| COPAGE-06 | P1 Company page: AC6 | Tasks | Pending |
| COPAGE-07 | P1 Company page: AC7 | Tasks | Pending |
| WHNEW-01 | P1 Create warehouse: AC1 | Tasks | Pending |
| WHNEW-02 | P1 Create warehouse: AC2 | Tasks | Pending |
| WHNEW-03 | P1 Create warehouse: AC3 | Tasks | Pending |
| WHNEW-04 | P1 Create warehouse: AC4 | Tasks | Pending |
| WHNEW-05 | P1 Create warehouse: AC5 | Tasks | Pending |
| WHNEW-06 | P1 Create warehouse: AC6 | Tasks | Pending |
| WHNEW-07 | P1 Create warehouse: AC7 | Tasks | Pending |
| WHNEW-08 | P1 Create warehouse: AC8 | Tasks | Pending |
| WHNEW-09 | P1 Create warehouse: AC9 | Tasks | Pending |
| ARCH-01 | P1 Archive: AC1 | Tasks | Pending |
| ARCH-02 | P1 Archive: AC2 | Tasks | Pending |
| ARCH-03 | P1 Archive: AC3 | Tasks | Pending |
| ARCH-04 | P1 Archive: AC4 | Tasks | Pending |
| ARCH-05 | P1 Archive: AC5 | Tasks | Pending |
| ARCH-06 | P1 Archive: AC6 | Tasks | Pending |
| ARCH-07 | P1 Archive: AC7 | Tasks | Pending |
| ARCH-08 | P1 Archive: AC8 | Tasks | Pending |
| ARCH-09 | P1 Archive: AC9 | Tasks | Pending |
| SEC-01 | P1 Session: AC1 | Tasks | Pending |
| SEC-02 | P1 Session: AC2 | Tasks | Pending |
| SEC-03 | P1 Session: AC3 | Tasks | Pending |
| EDGE-01 | Edge 1 | Tasks | Pending |
| EDGE-02 | Edge 2 | Tasks | Pending |
| EDGE-03 | Edge 3 | Tasks | Pending |
| EDGE-04 | Edge 4 | Tasks | Pending |
| EDGE-05 | Edge 5 | Tasks | Pending |
| EDGE-06 | Edge 6 | Tasks | Pending |
| EDGE-07 | Edge 7 | Tasks | Pending |
| EDGE-08 | Edge 8 | Tasks | Pending |
| EDGE-09 | Edge 9 | Tasks | Pending |

**Coverage:** 53 total, 0 mapped to tasks, 53 unmapped ⚠️ (mapped in tasks.md)

## Sources

- GitHub issue #34 - scope and acceptance summary
- README endpoint table and `CompanyController` / `WarehouseController` - endpoints, roles, `includeArchived`
- `CompanyExceptionHandler` / `WarehouseExceptionHandler` and domain exceptions - status codes and `detail` texts mapped to pt-BR
- `.specs/features/30-frontend-auth/spec.md`, `.specs/features/32-org-switch/spec.md` - BFF, session and error-message rules reused

## Success Criteria

- [ ] An `OWNER` creates, archives and unarchives a company and a warehouse from the browser, in an E2E run against the real backend.
- [ ] `./mvnw verify` and the frontend lint/typecheck/test/build/check:bundle/E2E pass.
