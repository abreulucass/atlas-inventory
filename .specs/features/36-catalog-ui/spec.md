# Catalog Screens Specification

**Approved**: 2026-11-27 - user: "aprovado"

## Problem Statement

The API already manages categories and products (feature 22), but the frontend has no catalog screen: an owner cannot register what the organization stocks without calling the API by hand. Stock movements and transfers screens need products to pick from, so the catalog comes next after companies and warehouses (feature 34).

## Goals

- [ ] An `OWNER` or `ADMIN` creates a category and a product in it, edits the product, and archives and unarchives both from the browser, in an E2E run against the real backend.
- [ ] Any member finds a product by name or SKU prefix, by category and by status, across pages, with the filters kept in the URL.
- [ ] A `MEMBER` sees the catalog with no write control; no token reaches page JavaScript (BFF rule of feature 30).

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Changing a product's SKU or unit | The API refuses it (`sku and unit cannot be changed`) |
| Renaming a category; deleting anything | The API offers neither |
| Stock balances or movements on catalog pages | Own issue (stock screens) |
| A product detail page | Every field fits in the table row and the edit dialog |
| Page size control and column sorting | The API orders by name; fixed 20 per page |
| Bulk import or bulk actions | Not asked |
| Backend changes | Every endpoint needed exists |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Navigation | Two sidebar items, "Produtos" → `/produtos` and "Categorias" → `/categorias` | User chose "Dois itens: Produtos e Categorias (Recommended)" | y |
| Product editing | In scope: "Editar" on a row opens a dialog with "Nome" and "Categoria"; SKU and unit shown read-only | User chose "Sim, dialog de edição (Recommended)" | y |
| Product list controls | Search, category filter, status filter (Ativos/Arquivados/Todos) and previous/next pagination, all in the URL (`?q=&categoria=&status=&pagina=`), read on the server | User chose "Tudo, estado na URL (Recommended)" | y |
| Unit display | Name and code, e.g. "Quilograma (KG)"; the code is what is sent | User chose "Nome + código (Recommended)" | y |
| Issue and branch | GitHub issue #36 created by the agent, branch `36-feature-catalog-ui` | Same flow as #34 | y |
| Unit names | UN Unidade, CX Caixa, PC Peça, KG Quilograma, G Grama, L Litro, ML Mililitro, M Metro | pt-BR names of the API's enum | n |
| Patterns reused | Create/edit in modal dialogs submitting Server Actions; "Arquivar" confirms, "Desarquivar" acts at once; write controls hidden for `MEMBER` (role from `GET /api/me`); skeleton while loading | Same decisions as feature 34 | n |
| Categories archived filter | Hidden by default; "Mostrar arquivadas" in the URL (`?arquivadas=1`) sends `includeArchived=true` | Same as the companies list | n |
| URL parameters | `pagina` is 1-based (`pagina=1` → API `page=0`); `status` is `ativos` (default), `arquivados` or `todos`; an invalid `pagina`, `status` or `categoria` falls back to its default instead of an error | A hand-edited URL never shows an error page | n |
| Changing search or a filter | Resets `pagina` to 1 | A filter shrinks the result; staying on page 5 would show an empty page | n |
| Category column and filter | Products page reads `GET /api/categories?includeArchived=true` to name each product's category; the filter lists every category, archived ones suffixed " (arquivada)" | A product may sit in an archived category and must still show and filter by it | n |
| Category options in create/edit | Only active categories, plus "Sem categoria"; in edit, a product already in an archived category shows it selected and may keep it | The API refuses an archived category only when it is being set (`409`) | n |
| Archived products | "Editar" hidden on archived rows | The API refuses to edit an archived product (`409`) | n |
| Edit request body | Sends `version`, `name` and `categoryId` (`null` for "Sem categoria") | One PATCH covers both fields; the version guards concurrent edits | n |
| Client-side validation | SKU 1–40 letters, digits, `.`, `_` or `-` after trim (case-insensitive, the backend upper-cases); product name 1–200 characters after trim; category name 1–80 characters after trim; unit required | Mirrors the backend rules for instant feedback | n |
| Telling `409`/`404` causes apart | The Server Action maps the backend `detail` prefix (`SKU already in use`, `Category name already in use`, `Category is archived`, `Product was changed by someone else`, `Product is archived`, `Category not found`, `Organization is archived`) to a pt-BR message | Same approach as feature 34; the API has no error code field | n |
| List failure, write failure, session | `401` → `/auth/expired`; other read errors → the app error boundary; write `403`, backend down and archived organization use the feature 34 messages | Same rules as features 30–34 | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Manage categories ⭐ MVP

**User Story**: As an `OWNER` or `ADMIN`, I want to create, archive and unarchive categories so that products can be grouped.

**Why P1**: A product's category must exist before it is chosen.

**Acceptance Criteria**:

1. The system SHALL show a sidebar item "Categorias" on every authenticated page, linking to `/categorias`.
2. WHEN a user opens `/categorias` THEN the system SHALL list the categories from `GET /api/categories` in the API's order, each with name and status ("Ativa" or "Arquivada").
3. WHILE "Mostrar arquivadas" is off the system SHALL request `GET /api/categories` without `includeArchived` and list no archived category.
4. WHEN the user turns "Mostrar arquivadas" on THEN the system SHALL request `GET /api/categories?includeArchived=true` and list archived categories with the badge "Arquivada".
5. WHEN the organization has no category to list THEN the system SHALL show "Nenhuma categoria cadastrada."
6. WHILE the list loads the system SHALL show a skeleton in place of the table.
7. WHILE the user's role is `OWNER` or `ADMIN` the system SHALL show a "Nova categoria" button on `/categorias`.
8. WHILE the user's role is `MEMBER` the system SHALL show no "Nova categoria" button and no "Arquivar" or "Desarquivar" control.
9. WHEN the user uses "Nova categoria" THEN the system SHALL open a dialog with the field "Nome" and the button "Criar categoria".
10. WHEN the user submits a valid category form THEN the system SHALL call `POST /api/categories` from the server with `name`, close the dialog and show the new category in the list.
11. IF the category name is blank or longer than 80 characters after trim THEN the system SHALL show "Informe um nome de até 80 caracteres." under "Nome" and send no request.
12. IF the API answers `409` with a duplicate category name THEN the system SHALL keep the dialog open and show "Já existe uma categoria com este nome." under "Nome".
13. WHILE the category creation request is in flight the system SHALL disable "Criar categoria".
14. WHILE the user's role is `OWNER` or `ADMIN` the system SHALL show "Arquivar" on each active category row and "Desarquivar" on each archived category row.
15. WHEN the user uses "Arquivar" on a category THEN the system SHALL open a confirmation dialog with the title "Arquivar categoria?" and the buttons "Cancelar" and "Arquivar".
16. WHEN the user confirms with "Arquivar" THEN the system SHALL call `POST /api/categories/{id}/archive` from the server, close the dialog and show the category as archived or remove it from a list that hides archived ones.
17. WHEN the user uses "Desarquivar" on a category THEN the system SHALL call `POST /api/categories/{id}/unarchive` from the server without a confirmation dialog and show the category as active.

**Independent Test**: E2E: an `OWNER` creates a category, archives it with confirmation, shows archived, unarchives it; Vitest for the page, roles and error mapping.

---

### P1: List and find products ⭐ MVP

**User Story**: As a member, I want to browse and search the product catalog so that I can find the item I need.

**Why P1**: Entry point for every product action and for later stock screens.

**Acceptance Criteria**:

1. The system SHALL show a sidebar item "Produtos" on every authenticated page, linking to `/produtos`.
2. WHEN a user opens `/produtos` THEN the system SHALL list the products from `GET /api/products` in the API's order, each with SKU, name, category name, unit as "<name> (<code>)" and status ("Ativo" or "Arquivado").
3. WHEN a product has no category THEN the system SHALL show "—" in the category column.
4. WHEN the user submits the search field "Buscar por nome ou SKU" with a text THEN the system SHALL navigate to `/produtos?q=<text>` and request `GET /api/products` with `q=<text>`.
5. WHEN the user picks a category in the filter "Categoria" THEN the system SHALL navigate with `categoria=<id>` and request `GET /api/products` with `categoryId=<id>`.
6. WHILE the status filter is "Ativos" (the default) the system SHALL request `GET /api/products` without `status`.
7. WHEN the user picks "Arquivados" or "Todos" in the status filter THEN the system SHALL request `GET /api/products` with `status=ARCHIVED` or `status=ALL` and show archived products with the badge "Arquivado".
8. WHEN the URL carries `pagina=<n>` with `n` ≥ 1 THEN the system SHALL request `GET /api/products` with `page=<n-1>`.
9. WHILE `totalPages` is greater than 1 the system SHALL show "Página <n> de <totalPages>" with the links "Anterior" and "Próxima", "Anterior" disabled on page 1 and "Próxima" disabled on the last page.
10. WHEN the user uses "Próxima" or "Anterior" THEN the system SHALL navigate to the next or previous `pagina` keeping `q`, `categoria` and `status`.
11. WHEN the user changes the search or a filter THEN the system SHALL drop `pagina` from the URL.
12. IF `pagina`, `status` or `categoria` in the URL is invalid THEN the system SHALL request the list with that parameter at its default instead of showing an error.
13. WHEN the organization has no product and no search or filter is set THEN the system SHALL show "Nenhum produto cadastrado."
14. WHEN a search or filter is set and matches no product THEN the system SHALL show "Nenhum produto encontrado."
15. WHILE the list loads the system SHALL show a skeleton in place of the table.
16. WHEN a product's category is archived THEN the system SHALL still show that category's name in its row.

**Independent Test**: Vitest of the page with a mocked API: URL → API parameters, empty states, pagination links; E2E searches a created product by SKU.

---

### P1: Create a product ⭐ MVP

**User Story**: As an `OWNER` or `ADMIN`, I want to register a product with SKU, name, unit and category so that stock can count it later.

**Why P1**: Stock movements need products.

**Acceptance Criteria**:

1. WHILE the user's role is `OWNER` or `ADMIN` the system SHALL show a "Novo produto" button on `/produtos`.
2. WHILE the user's role is `MEMBER` the system SHALL show no "Novo produto" button.
3. WHEN the user uses "Novo produto" THEN the system SHALL open a dialog with the fields "SKU", "Nome", "Unidade" and "Categoria (opcional)" and the button "Criar produto".
4. The system SHALL offer in "Unidade" the eight units as "<name> (<code>)" and in "Categoria (opcional)" "Sem categoria" plus every active category.
5. WHEN the user submits a valid product form THEN the system SHALL call `POST /api/products` from the server with `sku`, `name`, `unit` and `categoryId` (`null` for "Sem categoria"), close the dialog and show the new product in the list.
6. IF the SKU is blank, longer than 40 characters or has characters other than letters, digits, `.`, `_` and `-` after trim THEN the system SHALL show "Use até 40 letras, números, ponto, hífen ou sublinhado." under "SKU" and send no request.
7. IF the product name is blank or longer than 200 characters after trim THEN the system SHALL show "Informe um nome de até 200 caracteres." under "Nome" and send no request.
8. IF no unit is chosen THEN the system SHALL show "Escolha uma unidade." under "Unidade" and send no request.
9. IF the API answers `409` with a duplicate SKU THEN the system SHALL keep the dialog open and show "Já existe um produto com este SKU." under "SKU".
10. IF the API answers `409` because the category is archived THEN the system SHALL keep the dialog open and show "Esta categoria está arquivada." under "Categoria (opcional)".
11. IF the API answers `404` because the category does not exist THEN the system SHALL keep the dialog open and show "Categoria não encontrada." under "Categoria (opcional)".
12. WHILE the product creation request is in flight the system SHALL disable "Criar produto".

**Independent Test**: E2E: an `OWNER` creates a product and sees it listed; Vitest for each validation and error mapping.

---

### P1: Edit a product ⭐ MVP

**User Story**: As an `OWNER` or `ADMIN`, I want to fix a product's name or move it to another category so that the catalog stays correct.

**Why P1**: User chose to ship editing with the catalog screens.

**Acceptance Criteria**:

1. WHILE the user's role is `OWNER` or `ADMIN` the system SHALL show "Editar" on each active product row.
2. WHILE the user's role is `MEMBER` or the product is archived the system SHALL show no "Editar" on that row.
3. WHEN the user uses "Editar" THEN the system SHALL open a dialog titled "Editar produto" showing SKU and unit as read-only text, the fields "Nome" and "Categoria (opcional)" filled with the current values, and the button "Salvar".
4. WHEN the user submits a valid edit form THEN the system SHALL call `PATCH /api/products/{id}` from the server with `version`, `name` and `categoryId`, close the dialog and show the new values in the row.
5. IF the product name is blank or longer than 200 characters after trim THEN the system SHALL show "Informe um nome de até 200 caracteres." under "Nome" and send no request.
6. IF the API answers `409` because the version is stale THEN the system SHALL keep the dialog open and show "Este produto foi alterado por outra pessoa. Recarregue a página."
7. IF the API answers `409` because the chosen category is archived THEN the system SHALL keep the dialog open and show "Esta categoria está arquivada." under "Categoria (opcional)".
8. WHILE the edit request is in flight the system SHALL disable "Salvar".
9. WHEN the product's current category is archived THEN the system SHALL show it selected in "Categoria (opcional)" so that saving without touching it keeps it.

**Independent Test**: E2E: an `OWNER` renames a product and sees the new name; Vitest for the stale-version mapping.

---

### P1: Archive and unarchive products ⭐ MVP

**User Story**: As an `OWNER` or `ADMIN`, I want to archive a product I no longer stock, and bring it back, so that the default list shows only what is in use.

**Why P1**: The only lifecycle action the API offers for products.

**Acceptance Criteria**:

1. WHILE the user's role is `OWNER` or `ADMIN` the system SHALL show "Arquivar" on each active product row and "Desarquivar" on each archived product row.
2. WHILE the user's role is `MEMBER` the system SHALL show neither "Arquivar" nor "Desarquivar" on product rows.
3. WHEN the user uses "Arquivar" on a product THEN the system SHALL open a confirmation dialog with the title "Arquivar produto?" and the buttons "Cancelar" and "Arquivar".
4. WHEN the user confirms with "Arquivar" THEN the system SHALL call `POST /api/products/{id}/archive` from the server, close the dialog and refresh the list with the current URL filters.
5. WHEN the user uses "Cancelar" in a confirmation dialog THEN the system SHALL close it and send no request.
6. WHEN the user uses "Desarquivar" on a product THEN the system SHALL call `POST /api/products/{id}/unarchive` from the server without a confirmation dialog and refresh the list with the current URL filters.

**Independent Test**: E2E: archive a product with confirmation, find it under "Arquivados", unarchive it.

---

### P1: Session and access rules ⭐ MVP

**User Story**: As the product owner, I want these screens to follow the BFF and tenant rules so that no token leaks.

**Why P1**: Security invariant of the project.

**Acceptance Criteria**:

1. The system SHALL perform every categories and products API call from the server with the access token from the httpOnly cookie.
2. The system SHALL never place a token in a Server Action response, a response body or header sent to the browser, or the client bundle.
3. WHEN a `MEMBER` opens `/produtos` or `/categorias` THEN the system SHALL list the organization's catalog with no write control.

**Independent Test**: E2E with a `MEMBER`; `check:bundle`.

---

## Edge Cases

1. IF a write returns `403` THEN the system SHALL show "Seu papel não permite esta operação." in the dialog or next to the action.
2. IF the backend is unavailable during a write THEN the system SHALL keep the session cookies and show "Serviço indisponível. Tente novamente em instantes." in the dialog or next to the action.
3. IF a write returns `401` THEN the system SHALL send the user through `/auth/expired`.
4. IF a write returns `409` because the organization is archived THEN the system SHALL show "Esta organização está arquivada." in the dialog or next to the action.
5. IF a read on `/produtos` or `/categorias` returns `401` THEN the system SHALL send the user through `/auth/expired`.
6. WHEN a page renders at 375 px wide THEN the system SHALL show no page-level horizontal scroll; a table wider than the viewport scrolls inside its own container.
7. WHEN a page renders at 1280 px wide THEN the system SHALL show every table column without horizontal scroll, with a 200-character product name kept inside its cell.
8. WHEN the search text has characters like `%`, `&` or spaces THEN the system SHALL send it URL-encoded and show it back in the search field.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| CAT-01 | P1 Categories: AC1 | Tasks | Pending |
| CAT-02 | P1 Categories: AC2 | Tasks | Pending |
| CAT-03 | P1 Categories: AC3 | Tasks | Pending |
| CAT-04 | P1 Categories: AC4 | Tasks | Pending |
| CAT-05 | P1 Categories: AC5 | Tasks | Pending |
| CAT-06 | P1 Categories: AC6 | Tasks | Pending |
| CAT-07 | P1 Categories: AC7 | Tasks | Pending |
| CAT-08 | P1 Categories: AC8 | Tasks | Pending |
| CAT-09 | P1 Categories: AC9 | Tasks | Pending |
| CAT-10 | P1 Categories: AC10 | Tasks | Pending |
| CAT-11 | P1 Categories: AC11 | Tasks | Pending |
| CAT-12 | P1 Categories: AC12 | Tasks | Pending |
| CAT-13 | P1 Categories: AC13 | Tasks | Pending |
| CAT-14 | P1 Categories: AC14 | Tasks | Pending |
| CAT-15 | P1 Categories: AC15 | Tasks | Pending |
| CAT-16 | P1 Categories: AC16 | Tasks | Pending |
| CAT-17 | P1 Categories: AC17 | Tasks | Pending |
| PLIST-01 | P1 List products: AC1 | Tasks | Pending |
| PLIST-02 | P1 List products: AC2 | Tasks | Pending |
| PLIST-03 | P1 List products: AC3 | Tasks | Pending |
| PLIST-04 | P1 List products: AC4 | Tasks | Pending |
| PLIST-05 | P1 List products: AC5 | Tasks | Pending |
| PLIST-06 | P1 List products: AC6 | Tasks | Pending |
| PLIST-07 | P1 List products: AC7 | Tasks | Pending |
| PLIST-08 | P1 List products: AC8 | Tasks | Pending |
| PLIST-09 | P1 List products: AC9 | Tasks | Pending |
| PLIST-10 | P1 List products: AC10 | Tasks | Pending |
| PLIST-11 | P1 List products: AC11 | Tasks | Pending |
| PLIST-12 | P1 List products: AC12 | Tasks | Pending |
| PLIST-13 | P1 List products: AC13 | Tasks | Pending |
| PLIST-14 | P1 List products: AC14 | Tasks | Pending |
| PLIST-15 | P1 List products: AC15 | Tasks | Pending |
| PLIST-16 | P1 List products: AC16 | Tasks | Pending |
| PNEW-01 | P1 Create product: AC1 | Tasks | Pending |
| PNEW-02 | P1 Create product: AC2 | Tasks | Pending |
| PNEW-03 | P1 Create product: AC3 | Tasks | Pending |
| PNEW-04 | P1 Create product: AC4 | Tasks | Pending |
| PNEW-05 | P1 Create product: AC5 | Tasks | Pending |
| PNEW-06 | P1 Create product: AC6 | Tasks | Pending |
| PNEW-07 | P1 Create product: AC7 | Tasks | Pending |
| PNEW-08 | P1 Create product: AC8 | Tasks | Pending |
| PNEW-09 | P1 Create product: AC9 | Tasks | Pending |
| PNEW-10 | P1 Create product: AC10 | Tasks | Pending |
| PNEW-11 | P1 Create product: AC11 | Tasks | Pending |
| PNEW-12 | P1 Create product: AC12 | Tasks | Pending |
| PEDIT-01 | P1 Edit product: AC1 | Tasks | Pending |
| PEDIT-02 | P1 Edit product: AC2 | Tasks | Pending |
| PEDIT-03 | P1 Edit product: AC3 | Tasks | Pending |
| PEDIT-04 | P1 Edit product: AC4 | Tasks | Pending |
| PEDIT-05 | P1 Edit product: AC5 | Tasks | Pending |
| PEDIT-06 | P1 Edit product: AC6 | Tasks | Pending |
| PEDIT-07 | P1 Edit product: AC7 | Tasks | Pending |
| PEDIT-08 | P1 Edit product: AC8 | Tasks | Pending |
| PEDIT-09 | P1 Edit product: AC9 | Tasks | Pending |
| PARCH-01 | P1 Archive products: AC1 | Tasks | Pending |
| PARCH-02 | P1 Archive products: AC2 | Tasks | Pending |
| PARCH-03 | P1 Archive products: AC3 | Tasks | Pending |
| PARCH-04 | P1 Archive products: AC4 | Tasks | Pending |
| PARCH-05 | P1 Archive products: AC5 | Tasks | Pending |
| PARCH-06 | P1 Archive products: AC6 | Tasks | Pending |
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

**Coverage:** 71 total, 0 mapped to tasks, 71 unmapped ⚠️ (mapped in tasks.md)

## Sources

- `.specs/STATE.md` handoff - "Next candidates: catalog screens"
- `CategoryController` / `ProductController`, `Products` / `Categories`, `ProductView`, `ProductPage`, `Unit`, `Sku` - endpoints, parameters, limits (category 80, product 200, SKU 40), page size 20
- `CatalogExceptionHandler` and domain exceptions - status codes and `detail` texts mapped to pt-BR
- `.specs/features/22-product-catalog/spec.md` - roles (any member reads, `OWNER`/`ADMIN` write)
- `.specs/features/34-companies-warehouses-ui/spec.md` - dialog, archive, role-hiding, session and error-message patterns reused

## Success Criteria

- [ ] An `OWNER` creates a category and a product, edits, archives and unarchives them from the browser, in an E2E run against the real backend.
- [ ] `./mvnw verify` and the frontend lint/typecheck/test/build/check:bundle/E2E pass.
