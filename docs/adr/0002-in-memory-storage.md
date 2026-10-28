# ADR-0002: In-memory payment storage

**Status:** Accepted, for this phase

## Context

The brief says a real storage engine isn't needed and an in-memory test double is fine.

## Decision

Store payments in a `ConcurrentHashMap` inside `PaymentsRepository`, whose methods return
`Mono`. It's a `ConcurrentHashMap` rather than a `HashMap`, as in the starter, because WebFlux
serves requests on several event-loop threads at once.

## Consequences

- Payments are lost on restart.
- **The gateway can't run as more than one instance.** Each instance would have its own payments,
  so a `GET` routed to a different instance from the `POST` would return `404`. This is the only
  state in the service.
- Moving to a real database means changing `PaymentsRepository`'s internals (or extracting an
  interface, if both implementations are needed); the service and API don't change. Its methods
  are reactive so that a reactive driver (R2DBC, or a reactive NoSQL client) fits without
  changing callers.
- A real store would need: a primary key on the payment id, a merchant id column for scoping,
  no card number or CVV columns, and an idempotency-key table (see
  [ADR-0003](0003-no-retries-on-payment-requests.md)).
