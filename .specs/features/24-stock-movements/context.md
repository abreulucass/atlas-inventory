# Stock Movements and Balance Context

**Gathered:** 2026-11-26
**Spec:** `.specs/features/24-stock-movements/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Per-warehouse stock: entry, exit and adjustment movements with a required idempotency key, the balance they produce (never negative, consistent under concurrency), and reads of balance and history.

---

## Implementation Decisions

### Movement types

- Entry, exit and adjustment; transfer comes in the next feature (user: "Entrada, saída e ajuste").

### Negative balance

- Refused with `409`; nothing recorded (user: "Bloqueia com 409").

### Permissions

- `OWNER`/`ADMIN` in any warehouse; `MEMBER` in warehouses visible through grants; reads follow the same visibility (user: "OWNER/ADMIN + MEMBER com acesso").

### Idempotency

- `Idempotency-Key` required; same key + same request replays the original `201`; same key + different request `422`; unique per organization, kept forever (user: "Idempotency-Key obrigatória").

### Agent's Discretion

None.

### Declined / Undiscussed Gray Areas → Assumptions

Endpoints, body, quantity format, adjustment as delta, note rules, key format and scope, archived resources, list shapes, precedence: logged in the spec's Assumptions with `n`.

---

## Specific References

No specific requirements - follows features 16, 20 and 22's conventions.

---

## Deferred Ideas

- Transfer between warehouses (next feature).
- Reservations, lots and expiry, minimum stock and alerts, valuation.
