# Stock Transfers Context

**Gathered:** 2026-11-26
**Spec:** `.specs/features/26-stock-transfers/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Transfer stock of one or more products from one warehouse to another of the same organization, atomically and idempotently, on the stock core of feature 24.

---

## Implementation Decisions

### Model

- Atomic, one step: the exit from the origin and the entry in the destination commit together or not at all; no in-transit state (user: "Atômica, um passo").

### Items

- Several items per transfer, all or nothing: if any item fails (e.g. insufficient balance), nothing is recorded (user: "Vários itens, tudo ou nada").

### Reach

- Any two warehouses of the organization, including across companies (ADR-002); never across organizations (user: "Qualquer da organização").

### Permissions

- `OWNER`/`ADMIN` between any warehouses; `MEMBER` only when their grants show both origin and destination (user: "OWNER/ADMIN + MEMBER nos dois").

### Agent's Discretion

None.

### Declined / Undiscussed Gray Areas → Assumptions

Endpoints, body, item limits, key namespace, "same request", read visibility, response shape, archived resources, precedence, lock ordering: logged in the spec's Assumptions with `n`.

---

## Specific References

No specific requirements - follows feature 24's conventions (quantity format, idempotency, `404` for invisible warehouses, ProblemDetail).

---

## Deferred Ideas

- Two-step transfer with in-transit stock, receipt and cancellation.
- Listing transfers (`GET /api/transfers`); the per-warehouse movement history already shows them.
- Reservations, lots and expiry.
