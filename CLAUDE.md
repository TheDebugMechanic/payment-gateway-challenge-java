# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Card payment gateway (Java 17, Spring Boot 4, reactive WebFlux, Gradle). `POST /v1/payments` validates a card, asks an acquiring bank simulator to authorize it, and stores the result (last four digits only); `GET /v1/payments/{id}` retrieves it. Started from Checkout.com's Java challenge skeleton; the brief is in `requirements.md`. Design rationale is in `docs/DESIGN.md`, ops notes in `docs/RUNBOOK.md`, and decision records in `docs/adr/` — read the relevant ADR before changing retries, storage, or card-data handling.

## Commands

```bash
docker compose up --build          # gateway on :8090 + bank simulator on :8080
docker compose up bank_simulator   # bank only, then run the gateway locally:
./gradlew bootRun

./gradlew test                     # unit, slice, component tests (no Docker)
./gradlew check                    # also *IT end-to-end tests (Testcontainers + Mountebank; needs Docker) and jacoco
./gradlew test --tests '*PaymentGatewayServiceTest'                 # single class
./gradlew test --tests '*PaymentGatewayServiceTest.someMethod'      # single method
./gradlew integrationTest          # only the *IT tests
```

Test classes ending `IT` are excluded from `test` and run only by `integrationTest`; name new end-to-end tests accordingly. CI runs `./gradlew check` and builds the Docker image.

The simulator (`imposters/bank_simulator.ejs`) decides by the card number's last digit: odd = Authorized, even = Declined, 0 = bank 503. `requests.http` has a ready request per outcome.

Config (env var overrides): `BANK_BASE_URL` (default `http://localhost:8080`), `BANK_CONNECT_TIMEOUT` (2s), `BANK_RESPONSE_TIMEOUT` (10s), `SERVER_PORT` (8090).

## Architecture

Layered under `com.checkout.payment.gateway`: `controller` → `service` → `client` (bank, WebClient) and `repository` (in-memory). These are deliberately concrete classes with no interfaces (one implementation each; see DESIGN.md) — don't add interfaces without a second implementation. `PaymentGatewayApi` holds the OpenAPI annotations, the controller implements it.

Key invariants that span several files:

- **Separate model per layer, and the full card number must never reach storage, responses, or logs.** `PostPaymentRequest` (API, holds validation rules) → `PaymentCommand` (service) → `BankPaymentRequest` (bank wire format, in `client`) all carry the PAN; `Payment` (stored) and `PaymentResponse` have no field for it, only last four. Keep it that way.
- **Outcome mapping drives the HTTP contract**: invalid request → `400` with `payment_status: Rejected` and the bank is never called; bank authorized/declined → `201` (declined is still a created payment); bank failure → `502` (nothing charged, safe to retry); bank timeout → `504` (outcome unknown, retry may double-charge). Failures store nothing. Errors are RFC 9457 problem details produced by `CommonExceptionHandler`.
- **No retries on payment requests** to the bank (ADR 0003) — a retry after timeout could charge twice.
- **Observability is part of the contract**: `X-Trace-Id` header (`TraceIdResponseFilter`), `Cache-Control: no-store` (`NoStoreFilter`), `traceparent` propagated to the bank, custom metrics in `PaymentMetrics` (`payments_processed_total{status}`, `payments_bank_failures_total`).
- JSON uses snake_case globally (`application.yml`). `PaymentRequestBinder` reads the POST body as a `JsonNode`, type-checks each field (so `10.5` for `amount` is rejected, not truncated), then runs Bean Validation, so all invalid fields are reported in one `400`; don't switch the controller back to `@Valid @RequestBody PostPaymentRequest`, which would stop at the first wrong-typed field. Currencies: USD, GBP, EUR. Custom constraints `@NotExpired` / `@SupportedCurrency` live in `validation`.

## Testing layers

Unit (fixed clock, Mockito) → bank contract (`AcquiringBankClientTest`, WireMock) → HTTP contract (`@WebFluxTest`) → component (`PaymentGatewayComponentTest`, full app + WireMock) → end-to-end (`PaymentGatewayIT`, real simulator). Put a new test at the lowest layer that can prove the behavior.

## Code conventions

- Every method, constructor, class, record and enum in `src/main` gets Javadoc (with `@param`/`@return` where they apply), including private methods. Use `{@inheritDoc}` for overrides that add nothing. Check with `./gradlew javadoc`.
- Every test class, `@Nested` class and test method (`@Test`, `@ParameterizedTest`) gets a `@DisplayName` written as a readable sentence. Test helper methods get Javadoc.
