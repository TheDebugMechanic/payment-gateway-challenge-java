# ADR-0001: Reactive stack (Spring WebFlux) on Java 17 and Spring Boot 4

**Status:** Accepted

## Context

A payment gateway spends almost all of a request waiting on the acquiring bank, often for
hundreds of milliseconds and up to the 10 s timeout. The stack has to handle many concurrent
requests that are mostly idle. The brief also asks for simple, maintainable code.

Three options were considered:

1. **Spring MVC with platform threads.** Simple, but each waiting request holds a thread, so
   concurrency is capped by the thread pool.
2. **Spring MVC with virtual threads.** Imperative code that scales like non-blocking code. The
   simplest option with good concurrency.
3. **Spring WebFlux (Reactor).** Non-blocking end to end: Netty, `WebClient` and a repository
   that returns `Mono`.

## Decision

Use **WebFlux**, on Java 17 (the version the challenge skeleton requires) and Spring Boot 4.1,
which supports Java 17 as its minimum.

Options 2 and 3 are both sound for this workload. WebFlux was chosen because the team building
it has production experience with Reactor, which makes it the lower-risk option to build and
operate here. It also adds backpressure, plus timeouts and cancellation that are part of the
pipeline itself (`.timeout()` on the bank call). Neither matters much at this size, but both
would matter for a gateway under load.

## Consequences

- Code has to stay non-blocking: no `.block()` on request paths. A blocking driver (for
  example JDBC) would need wrapping or a reactive alternative such as R2DBC.
- Trace context doesn't follow a request across Reactor threads on its own. This is handled
  with `spring.reactor.context-propagation=auto`, and the component test checks that the
  trace reaches the bank.
- Stack traces are harder to read than in imperative code, and Reactor has a learning curve
  for engineers new to it.
- If the team changed, **MVC with virtual threads would be a reasonable move**. The business
  logic (`PaymentGatewayService`, the models) would barely change; most of the work would be swapping
  `Mono` for plain return values.
