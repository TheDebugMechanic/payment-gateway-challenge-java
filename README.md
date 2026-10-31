# Payment Gateway

An API that lets a merchant take a card payment and look it up later. The gateway validates the
card details, asks the acquiring bank (a simulator) to authorize the payment, and stores the
result, keeping only the last four card digits.

Built with Java 17, Spring Boot 4 and WebFlux on Gradle, starting from Checkout.com's
[Java challenge skeleton](https://github.com/cko-recruitment/payment-gateway-challenge-java)
(its commits are the start of this repository's history). The original brief is in
[requirements.md](requirements.md). Design decisions and assumptions are in
[docs/DESIGN.md](docs/DESIGN.md), and operating notes (metrics, alerts, what to do when
the bank misbehaves) are in [docs/RUNBOOK.md](docs/RUNBOOK.md).

## Quick start

You need Docker, and JDK 17 or newer to build and test outside Docker (as the skeleton
requires).

```bash
docker compose up --build
```

This starts the gateway on **:8090** and the bank simulator on :8080. Take a payment:

```bash
curl -i -X POST localhost:8090/v1/payments \
  -H 'Content-Type: application/json' \
  -d '{"card_number": "2222405343248877", "expiry_month": 4, "expiry_year": 2030,
       "currency": "GBP", "amount": 1050, "cvv": "123"}'
```

```http
HTTP/1.1 201 Created
Location: /v1/payments/2e3781f1-56f9-4bd2-991a-e406a6fff499
X-Trace-Id: 75fb5b4f468c76d13192aeff6af9a501

{"id": "2e3781f1-56f9-4bd2-991a-e406a6fff499", "status": "Authorized",
 "card_number_last_four": "8877", "expiry_month": 4, "expiry_year": 2030,
 "currency": "GBP", "amount": 1050}
```

Then retrieve it with `curl localhost:8090/v1/payments/<id>`.

[requests.http](requests.http) has one request per outcome (authorized, declined, bank error,
rejected, not found), ready to run from IntelliJ or VS Code.

The simulator decides by the card number's last digit: odd is **Authorized**, even is
**Declined**, and 0 makes the bank fail with a 503.

## API

Interactive docs: <http://localhost:8090/swagger-ui.html> (OpenAPI JSON at `/v3/api-docs`).

| Request                    | Outcome                               | Response                                           |
|----------------------------|---------------------------------------|----------------------------------------------------|
| `POST /v1/payments`        | Bank authorized or declined           | `201` with the payment and a `Location` header     |
|                            | Invalid request, bank not called      | `400` problem with `"payment_status": "Rejected"` and an `errors` list |
|                            | Bank failed, nothing was charged      | `502` problem. It is safe to try again             |
|                            | Bank timed out, outcome unknown       | `504` problem. Trying again may charge twice       |
| `GET /v1/payments/{id}`    | Found                                 | `200` with the payment                             |
|                            | Unknown id                            | `404` problem                                      |

Payment responses carry `Cache-Control: no-store` and an `X-Trace-Id` header.

Amounts are integers in the currency's minor unit (`1050` = £10.50). Accepted currencies are
USD, GBP and EUR. Errors use [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details:

```json
{
  "title": "Payment rejected", "status": 400,
  "detail": "One or more fields are invalid", "instance": "/v1/payments",
  "payment_status": "Rejected",
  "errors": [
    {"field": "card_number", "message": "must be 14-19 digits"},
    {"field": "expiry_year", "message": "card has expired"}
  ]
}
```

## Running locally without Docker for the gateway

```bash
docker compose up bank_simulator   # the bank only
./gradlew bootRun                  # the gateway on :8090
```

## Tests

```bash
./gradlew test    # unit, slice and component tests, no Docker needed
./gradlew check   # also the end-to-end tests against the real simulator (needs Docker)
```

Test reports are in `build/reports/tests/`, and coverage in `build/reports/jacoco/test/html/`.

| Layer         | Tests                                                     | Checks                                                       |
|---------------|-----------------------------------------------------------|--------------------------------------------------------------|
| Unit          | `PostPaymentRequestValidationTest`, `PaymentRequestBinderTest`, `PaymentGatewayServiceTest`, `PaymentsRepositoryTest`, `BankPropertiesTest` | Every rule in the brief at its boundaries, with a fixed clock; outcome mapping; nothing stored on bank failure |
| Bank contract | `AcquiringBankClientTest` (WireMock)                      | Exact wire format, decline, 4xx/5xx, timeout, unreachable bank, no retries |
| HTTP contract | `PaymentGatewayControllerTest` (`@WebFluxTest`)           | Status codes, JSON shape, problem details, card number never echoed |
| Component     | `PaymentGatewayComponentTest` (full app + WireMock)       | Real wiring and config, trace propagation to the bank, metrics |
| End to end    | `PaymentGatewayIT` (Testcontainers + Mountebank)          | Authorized, declined, bank error and retrieval against the real simulator |

CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs `./gradlew check` and builds the
image on every push and pull request.

## Observability

| What    | Where                                                                                      |
|---------|--------------------------------------------------------------------------------------------|
| Health  | `/actuator/health`, with `/liveness` and `/readiness` probes                                |
| Metrics | `/actuator/prometheus`: `payments_processed_total{status}`, `payments_bank_failures_total`, plus `http_server_requests_*` and `http_client_requests_*{uri="/payments"}` for bank latency and errors |
| Traces  | OpenTelemetry. Every response has an `X-Trace-Id` header, and the trace is passed to the bank in `traceparent` |
| Logs    | In Docker, one JSON object per line (Elastic Common Schema) with `traceId` and `spanId`. Card numbers and CVVs are never logged |

To see traces and metrics in Grafana, run the overlay and open <http://localhost:3000>
(Explore → Tempo for traces, Prometheus for metrics):

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up --build
```

## Configuration

| Property (environment variable)              | Default                 |
|----------------------------------------------|-------------------------|
| `bank.base-url` (`BANK_BASE_URL`)            | `http://localhost:8080` |
| `bank.connect-timeout` (`BANK_CONNECT_TIMEOUT`) | `2s`                 |
| `bank.response-timeout` (`BANK_RESPONSE_TIMEOUT`) | `10s`              |
| `server.port` (`SERVER_PORT`)                | `8090`                  |

## Project layout

```
src/main/java/com/checkout/payment/gateway/
  controller/     PaymentGatewayController: the HTTP endpoints
  service/        PaymentGatewayService (the payment flow) and PaymentMetrics
  client/         AcquiringBankClient: calls the bank (WebClient), with its settings
  repository/     PaymentsRepository: in-memory storage
  model/          Request, response and stored payment types
  validation/     PaymentRequestBinder (type-checks and validates the body, reporting every
                  invalid field at once) and the card expiry and currency constraints
  exception/      Exceptions and CommonExceptionHandler (RFC 9457 problem details)
  enums/          PaymentStatus
  filter/         X-Trace-Id and Cache-Control response headers
  configuration/  Clock, observability, OpenAPI
docs/
  DESIGN.md       Design, assumptions and trade-offs
  RUNBOOK.md      Running it, metrics, alerts and incident playbooks
  adr/            Architecture decision records
```
