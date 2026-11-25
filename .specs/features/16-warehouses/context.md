# Warehouses Context

**Gathered:** 2026-11-25
**Spec:** `.specs/features/16-warehouses/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Warehouses inside companies: domain, table with RLS and HTTP API (create, list, read, archive, unarchive), under feature 14's role rule.

---

## Implementation Decisions

### Scope

- Domain and HTTP API in one feature (user: "Uma feature só").

### Fields

- Name and code, both unique per company (user: "Nome + código").

### Permissions

- Per-company / per-warehouse permissions deferred; any member reads, `OWNER`/`ADMIN` write (user: "Adiar").

### Archived company

- Creating a warehouse in an archived company answers `409`; archiving a company does not touch its warehouses; routes nested under `/api/companies/{id}/warehouses` (user: "Bloquear criação").

### Agent's Discretion

None.

### Declined / Undiscussed Gray Areas → Assumptions

Code format, name rules, archive/unarchive in an archived company, `404` rules, response body: logged in the spec's Assumptions with `n`.

---

## Specific References

No specific requirements - follows feature 14's conventions.

---

## Deferred Ideas

- Per-company / per-warehouse permissions: own feature, before stock.
