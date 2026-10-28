# Runbook

For whoever operates the payment gateway: how to run it, what healthy looks like, and what to
do when it isn't. For how it's built and why, see [DESIGN.md](DESIGN.md).

## The service at a glance

| | |
|---|---|
| **What it does** | Takes card payments from merchants (`POST /v1/payments`), gets them authorized by the acquiring bank, and lets merchants look them up (`GET /v1/payments/{id}`) |
| **Port** | `8090` |
| **Depends on** | The acquiring bank (here, the simulator at `BANK_BASE_URL`). Nothing else |
| **State** | Payments are held **in memory**: a restart loses them, and only **one instance** may run ([ADR-0002](adr/0002-in-memory-storage.md)) |
| **Card data** | The full card number and CVV are never stored or logged ([ADR-0004](adr/0004-card-data-handling.md)). If you ever see one in a log, treat it as a security incident |

## Running it

```bash
docker compose up --build      # gateway on :8090, bank simulator on :8080
docker compose logs -f payment_gateway
docker compose down
```

The image is self-contained (Java 17 runtime, non-root user) and is configured only through
environment variables:

| Variable                | Default                 | Notes                                                    |
|-------------------------|-------------------------|----------------------------------------------------------|
| `BANK_BASE_URL`         | `http://localhost:8080` | Must have a valid hostname: no underscores, see below     |
| `BANK_CONNECT_TIMEOUT`  | `2s`                    | How long to wait for a connection to the bank            |
| `BANK_RESPONSE_TIMEOUT` | `10s`                   | How long to wait for the bank's answer before a `504`    |
| `SERVER_PORT`           | `8090`                  |                                                          |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | unset (plain text) | Set to `ecs` for one JSON object per log line. Compose sets it |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | unset (no export) | Where to send traces, for example `http://collector:4318/v1/traces` |

**On an orchestrator** (Kubernetes or similar):

- Liveness probe: `GET /actuator/health/liveness`. Readiness probe: `GET /actuator/health/readiness`.
- **Replicas: exactly 1.** With in-memory storage, a second instance has its own payments, so a
  `GET` routed to it returns `404` for a payment the other instance created.
- The JVM sizes its heap to 75% of the container's memory limit, so set a memory limit.
- A rollback is a redeploy of the previous image tag. **Any restart, including a rollback, loses
  all stored payments.** Merchants retrieving older payments will get `404`.

## What healthy looks like

| Check | Healthy |
|-------|---------|
| `GET /actuator/health/readiness` | `200 {"status":"UP"}` |
| Bank failure ratio (query below) | Close to 0% |
| Bank p99 latency | Well under the 10 s response timeout |
| `504` responses | None |
| Rejected ratio | Stable for each merchant; a sudden jump usually means a merchant's integration broke |

The bank is deliberately **not** part of readiness. If it were, a bank outage would take every
gateway instance out of the load balancer and turn a partial outage into a total one. A bank
outage shows up in the metrics and as `502` responses instead.

## Metrics

Scraped from `GET /actuator/prometheus`. Every series has `application="payment-gateway"`.

| Metric | Meaning |
|--------|---------|
| `payments_processed_total{status="authorized"\|"declined"\|"rejected"}` | Payment requests by outcome |
| `payments_bank_failures_total` | Bank calls that errored, couldn't connect or timed out |
| `http_client_requests_seconds_*{uri="/payments",status,outcome}` | Calls to the bank: count, latency histogram, status |
| `http_server_requests_seconds_*{uri,status}` | Requests to the gateway: count, latency histogram, status |

Useful queries:

```promql
# Payments per second, by outcome
sum by (status) (rate(payments_processed_total[5m]))

# Share of bank calls that failed
rate(payments_bank_failures_total[5m])
  / (rate(payments_bank_failures_total[5m])
     + sum(rate(payments_processed_total{status=~"authorized|declined"}[5m])))

# Bank latency, p99
histogram_quantile(0.99, sum by (le) (rate(http_client_requests_seconds_bucket{uri="/payments"}[5m])))

# What the bank is answering
sum by (status) (rate(http_client_requests_seconds_count{uri="/payments"}[5m]))

# What the gateway is answering
sum by (uri, status) (rate(http_server_requests_seconds_count{uri=~"/v1/payments.*"}[5m]))

# Share of payment requests rejected by validation
sum(rate(payments_processed_total{status="rejected"}[5m]))
  / sum(rate(payments_processed_total[5m]))
```

## Suggested alerts

| Alert | Condition | Severity | Playbook |
|-------|-----------|----------|----------|
| Gateway down | Readiness probe failing for 2 min | Page | [Gateway won't start or keeps restarting](#gateway-wont-start-or-keeps-restarting) |
| Bank failing | Bank failure ratio > 5% for 5 min | Page | [Merchants are getting 502](#merchants-are-getting-502) |
| Bank timeouts | Any `504` in 5 min | Ticket, page if sustained | [Merchants are getting 504](#merchants-are-getting-504) |
| Bank slow | Bank p99 > 5 s (half the timeout) for 10 min | Warn | [Merchants are getting 504](#merchants-are-getting-504) |
| Rejections up | Rejected ratio doubles against the same hour last week | Warn | [Rejections have jumped](#rejections-have-jumped) |

## Finding out what happened to a request

Every payment response carries an **`X-Trace-Id`** header. When a merchant reports a problem, ask
for it (or for the payment id).

- **Logs:** filter on `traceId` (or on `payment_id` for a processed payment). With JSON logs:
  ```bash
  docker compose logs payment_gateway | grep '"traceId":"<trace id>"'
  ```
- **Traces:** search for the trace id in Tempo (or your tracing backend) to see the request and
  its bank call, with timings.

Log lines to know:

| Level | Message | Meaning |
|-------|---------|---------|
| INFO | `Payment processed` with `payment_id`, `payment_status`, `amount`, `currency`, `card_last_four` | The bank decided; the payment is stored |
| WARN | `Acquiring bank returned 503 SERVICE_UNAVAILABLE: …` | The bank answered with an error → `502` |
| WARN | `Could not reach acquiring bank: …` | Connection failed (DNS, refused, connect timeout) → `502` |
| WARN | `Acquiring bank did not respond within PT10S` | Response timeout → `504`, outcome unknown |

Rejected requests are **not** logged, only counted, so validation details aren't in the logs. The
merchant receives the full list of invalid fields in the `400` response.

## Playbooks

### Merchants are getting 502

The bank answered with an error, or couldn't be reached. **No money moved**, so merchants can
safely retry.

1. Look at what the bank is answering (query above), and at the WARN log lines.
2. **Bank 5xx, or `Could not reach acquiring bank`:** the problem is on the bank's side, or in the
   network between us. Check the bank's status and whether `BANK_BASE_URL` resolves from the
   gateway's host or container. There's nothing to fix in the gateway; it recovers by itself when
   the bank does.
3. **Bank 4xx:** the gateway is sending requests the bank rejects. That's our bug. Validation
   should make it impossible. Check for a recent deployment and roll it back, then raise it with
   the team, quoting a trace id.

### Merchants are getting 504

The bank didn't answer within `BANK_RESPONSE_TIMEOUT`. **The outcome is unknown:** the bank may
have authorized the payment. Nothing was stored, and the gateway doesn't retry.

1. Look at bank p99 latency. If it's creeping up towards the timeout, the bank is slowing down:
   raise it with the bank.
2. **Each `504` needs settling with the bank.** Use the time, amount and card's last four digits
   from the trace or logs to ask the bank whether an authorization exists, and void it if needed.
3. Tell affected merchants not to retry blindly: a retry can charge the shopper twice. Safe retries
   need idempotency keys, which aren't built yet ([ADR-0003](adr/0003-no-retries-on-payment-requests.md)).
4. Only raise `BANK_RESPONSE_TIMEOUT` if the bank is known to be slow but healthy. A longer
   timeout means fewer `504`s, but each slow request holds a connection for longer.

### Rejections have jumped

Requests are failing validation before they reach the bank. This is almost always one merchant's
integration, often just after they deployed.

1. Check whether it's every merchant or one. Without authentication the gateway can't tell
   merchants apart, so use your edge or load balancer logs.
2. Reproduce with the merchant's request (minus the real card number) using
   [requests.http](../requests.http). The `400` body lists every invalid field.
3. If **every** merchant is affected at once, suspect a gateway change. A common one is the
   expiry check at a month boundary: a card expiring this month is valid until the month ends, in
   UTC. Roll back if a recent deployment lines up.

### Merchants get 404 for a payment they created

1. **Has the gateway restarted?** Payments are in memory, so any restart, crash or rollback loses
   all of them. Check the container's start time and uptime.
2. **Is more than one instance running?** Each has its own payments. Scale to exactly one.
3. Otherwise, check the id: an unknown id is a `404`, a malformed one is a `400`.

### Gateway won't start or keeps restarting

Look at the first `ERROR` in the startup logs.

| Log message | Cause | Fix |
|-------------|-------|-----|
| `bank.base-url has no valid host: …` | `BANK_BASE_URL` has a host Java can't parse, typically an underscore (`http://bank_simulator:8080`) | Use a hostname with hyphens. In compose, the simulator is reachable as `bank-simulator` |
| `Port 8090 was already in use` | Something else is on the port | Free the port or set `SERVER_PORT` |
| Container marked unhealthy, then restarted | Readiness didn't come up within the health-check start period (30 s) | Check memory limits and the startup logs. The JVM may be starved |

## Known limitations

Worth knowing during an incident. Each is a deliberate "not yet", explained in
[DESIGN.md](DESIGN.md#not-built-yet).

- No authentication: anyone who can reach the gateway can create or read any payment.
- No idempotency: merchants can't safely retry after a `504`.
- In-memory storage: one instance only, and payments are lost on restart.
- Bank failures aren't stored, only logged and counted, so settling a `504` depends on logs or
  traces.
- No circuit breaker: during a bank outage every request still waits for the bank's error, or for
  up to 10 s on a timeout.
