# Product Catalog Context

**Gathered:** 2026-11-26
**Spec:** `.specs/features/22-product-catalog/spec.md`
**Status:** Ready for tasks

---

## Feature Boundary

Organization-level catalog: flat categories and products (SKU, name, fixed unit, optional category), with optimistic-locked edits and a paginated, searchable product list.

---

## Implementation Decisions

### Next feature and scope

- Catalog is the next feature (user: "Catálogo de produtos").
- Products and categories; suppliers deferred to purchasing (user: "Produtos + categorias").

### Ownership

- Catalog belongs to the organization, shared by its companies (user: "Organização").

### Fields

- SKU unique per organization, name, unit, optional category; no price, no barcode (user: "SKU + nome + unidade + categoria").

### Units and categories

- Fixed unit list that says whether a quantity may be fractional; flat categories (user: "Unidade fixa, categoria plana").

### Editing

- Name and category editable with optimistic locking (`409` on stale version); SKU and unit immutable (user: "Edita nome/categoria; SKU e unidade fixos").

### Listing

- Paginated, search by name or SKU, filter by category and status (user: "Paginada + busca + filtro").

### Agent's Discretion

None.

### Declined / Undiscussed Gray Areas → Assumptions

Permissions, unit list, SKU/name formats, archived category rules, PATCH semantics, list parameters, endpoints, bodies, precedence: logged in the spec's Assumptions with `n`.

---

## Specific References

No specific requirements - follows features 14 and 16's conventions.

---

## Deferred Ideas

- Suppliers: with purchasing.
- Price/cost, barcode, category hierarchy, category rename.
