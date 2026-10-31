# Future enhancements and proposals

What to build next, in rough priority order, and why. This extends the
["Not built yet"](DESIGN.md#not-built-yet) table in DESIGN.md with the reasoning, a sketch of the
approach and a way to know it's done. Each item is a proposal, not a commitment; the first
three change what the service guarantees and should get an ADR before any code.

## How to read this

- **Priority:** P1 closes a correctness or safety gap, P2 is needed to run it for real, P3 is
  worth doing once those are in.
- **Effort:** S (a day or less), M (a few days), L (a week or more).
- **Done when** is the check that tells you the item is finished.

| # | Proposal | Priority | Effort |
|---|----------|----------|--------|
| 1 | [Idempotency keys](#1-idempotency-keys) | P1 | M |
| 2 | [Persistent storage](#2-persistent-storage) | P1 | M |
| 3 | [Authentication and merchant scoping](#3-authentication-and-merchant-scoping) | P1 | M |
| 4 | [TLS and card tokenization](#4-tls-and-card-tokenization) | P1 | L |
| 5 | [Bank resilience: circuit breaker and bulkhead](#5-bank-resilience-circuit-breaker-and-bulkhead) | P2 | M |
| 6 | [Record failed attempts and settle timeouts](#6-record-failed-attempts-and-settle-timeouts) | P2 | M |
| 7 | [Logs in Grafana and ready-made dashboards](#7-logs-in-grafana-and-ready-made-dashboards) | P2 | S |
| 8 | [Per-currency validation and more currencies](#8-per-currency-validation-and-more-currencies) | P3 | S |
| 9 | [Rate limiting](#9-rate-limiting) | P3 | S |
| 10 | [Contract tests against the real bank API](#10-contract-tests-against-the-real-bank-api) | P3 | M |
| 11 | [Smaller improvements](#11-smaller-improvements) | P3 | S |

## 1. Idempotency keys

**Problem.** A `POST` that times out (`504`) has an unknown outcome, and a retry can charge the
shopper twice ([ADR-0003](adr/0003-no-retries-on-payment-requests.md)). Merchants also can't safely
retry after a network failure of their own.

**Proposal.** Accept an `Idempotency-Key` header on `POST /v1/payments`.
- Store the response per merchant and key, with a TTL (24 hours is typical).
- A repeat request with the same key and the same body replays the stored response, without calling
  the bank. The same key with a different body is a `422`.
- Pass a derived key to the bank so that a retry after a timeout returns the original
  authorization instead of creating a new one.
- While the first request is still running, a second one with the same key waits for it or gets a
  `409`, never a second bank call.

**Needs.** Persistent storage (#2) so keys survive restarts and instances, and a decision on
whether the key is required or optional.

**Done when.** Sending the same request twice charges the bank once, a simulated `504` followed by a
retry with the same key doesn't create a second authorization, and tests cover the concurrent case.

## 2. Persistent storage

**Problem.** Payments live in a `ConcurrentHashMap` ([ADR-0002](adr/0002-in-memory-storage.md)):
they are lost on restart and only one instance can run.

**Proposal.** Put a database behind `PaymentsRepository`.
- A reactive driver (R2DBC with PostgreSQL) fits the non-blocking stack
  ([ADR-0001](adr/0001-reactive-webflux.md)); the repository already returns `Mono`.
- Columns: id (primary key), merchant id, status, last four, expiry, currency, amount, created
  time. **No card number or CVV column.**
- Schema migrations with Flyway or Liquibase.
- Extract an interface only now, because there will be two implementations (in-memory for fast
  tests, database for production).

**Done when.** A payment survives a restart, two instances behind a load balancer can serve each
other's payments, and the runbook's "exactly one replica" rule is removed.

## 3. Authentication and merchant scoping

**Problem.** Anyone who can reach the gateway can create and read any payment.

**Proposal.**
- Authenticate merchants with API keys or OAuth 2 client credentials. Often best enforced at an API
  gateway in front of the service, which can also do rate limiting (#9).
- Store the merchant id on each payment; return `404` (not `403`) for another merchant's payment so
  ids can't be probed.
- Put the merchant id in logs, traces and metric labels (mind label cardinality).

**Done when.** A merchant can't read another merchant's payment, unauthenticated requests get
`401`, and rejection ratios can be broken down per merchant (the runbook currently can't).

## 4. TLS and card tokenization

**Problem.** Card numbers and CVVs travel over plain HTTP in the demo setup and sit in JVM memory
as `String`s ([ADR-0004](adr/0004-card-data-handling.md)).

**Proposal.**
- TLS on the merchant-facing connection and on the call to the bank (at the load balancer or in
  the app).
- Tokenize the card number at the edge with a vault, so the gateway handles a token and only the
  component that calls the bank turns it back into a card number. This takes the gateway out of
  most of the PCI scope.
- Keep the CVV in memory only, for as short as possible, and never log or persist it.
- Review dependency, image and secret handling as part of the same effort.

**Done when.** No raw card number reaches the gateway's logs, memory dumps or storage in a
production-like setup, and a security review signs it off.

## 5. Bank resilience: circuit breaker and bulkhead

**Problem.** During a bank outage every request still waits for the bank's error, or up to the
10 s response timeout, tying up connections.

**Proposal.**
- A circuit breaker (Resilience4j) on `AcquiringBankClient`: after a run of failures, fail fast with
  `502` and probe periodically for recovery.
- A bulkhead to cap concurrent bank calls so a slow bank can't exhaust the gateway.
- **Never retry the payment call itself** (still true); only the breaker and bulkhead are added.
- Metrics for breaker state, and an alert when it opens.

**Done when.** With the bank down, requests fail in milliseconds instead of seconds, and the
breaker closes by itself when the bank returns.

## 6. Record failed attempts and settle timeouts

**Problem.** `502` and `504` outcomes are logged and counted but not stored, so settling a `504`
with the bank depends on searching logs ([runbook](RUNBOOK.md#merchants-are-getting-504)).

**Proposal.**
- Store an attempt record for every bank call that failed or timed out (time, amount, currency,
  last four, merchant, trace id, outcome). Still no card number or CVV.
- A periodic reconciliation job, or an admin endpoint, to ask the bank whether a timed-out attempt
  was authorized, and void it if needed.
- Expose attempt counts in metrics.

**Done when.** Support can list every unsettled `504` without grepping logs, and each one ends in a
recorded resolution.

## 7. Logs in Grafana and ready-made dashboards

**Problem.** Logs only go to the container's standard output, so Grafana's Loki source is empty,
and the metrics and traces have to be queried by hand.

**Proposal.**
- Send logs to Loki over OTLP (or scrape the container logs), keeping the `traceId` field so a
  trace links to its logs.
- Provision a Grafana dashboard from JSON with the stack: outcome rate, bank failure ratio, p95 and
  p99 latency, status codes, and a rejected-ratio panel. Use the queries in the
  [runbook](RUNBOOK.md#querying-observability-data).
- Add the alert rules from the runbook's "Suggested alerts" table as code.
- Make the metric names consistent between the scrape (`_seconds_`) and OTLP (`_milliseconds_`),
  or document a recording rule that unifies them.

**Done when.** `docker compose` with both files shows a populated dashboard and linked logs and
traces with no manual setup.

## 8. Per-currency validation and more currencies

**Proposal.**
- Read the accepted currencies from configuration instead of a hard-coded pattern.
- Validate precision per currency (JPY has no minor unit; some currencies have three decimals).
- Consider zero-amount card verification as a separate, explicit operation.

**Done when.** Adding a currency is a config change, and `amount` rules follow the currency's
minor unit.

## 9. Rate limiting

**Proposal.** Limit requests per merchant, ideally at the API gateway. If it has to be in the
service, use a token bucket keyed by merchant id and return `429` with `Retry-After`. Needs #3 to
identify merchants.

## 10. Contract tests against the real bank API

**Problem.** The bank client is tested against WireMock stubs and the simulator, which only model
the bank as the brief describes it.

**Proposal.** When a real acquirer is chosen, add consumer-driven contract tests (for example
Pact) or a sandbox test suite, and extract an interface for `AcquiringBankClient` at the same
time, since there will be two implementations.

## 11. Smaller improvements

- Return the bank's authorization code to the merchant (it is currently ignored) if the product
  needs it, kept apart from the gateway's own payment id.
- Support extra, lenient request validation modes only if a merchant integration asks for them;
  the strict type rules in `PaymentRequestBinder` are deliberate.
- Add an `Idempotency-Key` and merchant id to the structured log line once those exist.
- Publish the OpenAPI spec as a build artifact and run a compatibility check in CI so breaking
  changes to `/v1` are caught.
- Lower trace sampling from 100% before any real traffic.
- Graceful shutdown tuning and a startup warm-up, if cold starts show up in latency.
- Load testing (for example k6) to measure the bank-latency ceiling and size the connection pool.

## Process for picking one up

1. Write or update an ADR for anything that changes a guarantee (#1 to #6).
2. Add the tests first at the cheapest layer that proves it (see the testing strategy in
   [DESIGN.md](DESIGN.md#testing-strategy)).
3. Update [DESIGN.md](DESIGN.md), the [runbook](RUNBOOK.md) and the README, then remove the item
   here.
