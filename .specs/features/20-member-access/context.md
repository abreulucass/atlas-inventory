# Member Access by Company and Warehouse Context

**Gathered:** 2026-11-25
**Spec:** `.specs/features/20-member-access/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Managers grant `MEMBER`s access to companies or warehouses; a `MEMBER` sees only what is granted. Second half of the split agreed in feature 18.

---

## Implementation Decisions

### Who is restricted
- Only `MEMBER`. User: "Só MEMBER (Recommended)".

### Granularity
- Company (with all its warehouses) and single warehouse. User: "Empresa e depósito (Recommended)".

### Level
- Visibility only for now. User: "Só acesso, por ora (Recommended)".

### Default
- None. User: "Nenhum (Recommended)".

### No access
- `404`. User: "404 (Recommended)".

### Who grants
- `OWNER` and `ADMIN`. User: "OWNER e ADMIN (Recommended)".

### API
- Under the member. User: "Sob o membro (Recommended)".

### Agent's Discretion

None delegated; remaining defaults are in the spec's Assumptions table with `n`.

### Declined / Undiscussed Gray Areas → Assumptions

Exact paths, grants kept across role changes, who reads grants, duplicate/missing grant answers: in the spec's Assumptions table.

---

## Specific References

No specific requirements - follows feature 18.

---

## Deferred Ideas

- Grant levels (view / operate) with the stock feature.
- Restricting `ADMIN` per company.
- Listing who can see a company or warehouse.
