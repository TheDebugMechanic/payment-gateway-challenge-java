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

With Grafana (traces and metrics), **always pass both files**, every time you start or restart
the gateway:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up --build
```

A plain `docker compose up` recreates the gateway without the overlay's settings, and it silently
stops sending data to Grafana ([see below](#grafana-shows-no-data)).

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
| `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` / `_URL` / `_STEP` | `false` / unset / `1m` | Push metrics over OTLP, for example `http://collector:4318/v1/metrics` every `10s`. The observability overlay sets these |

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

**Names differ between the two ways of collecting metrics.** `/actuator/prometheus` (the scrape)
reports latency in seconds: `http_server_requests_seconds_*`. In Grafana with the observability
overlay, the metrics are pushed over OTLP and are named in milliseconds:
`http_server_requests_milliseconds_*` and `http_client_requests_milliseconds_*`. The queries below
use the scrape names; in Grafana, replace `_seconds_` with `_milliseconds_` (and read latencies in
ms). The `payments_*` metrics are named the same in both.

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

## Querying observability data

Start the stack with Grafana first ([Running it](#running-it)): both compose files. Then open
<http://localhost:3000> (the `grafana/otel-lgtm` image; if it asks for a login, use `admin` /
`admin`). Send some payments, or there is nothing to see.

| Signal | Where it is | How to look |
|--------|-------------|-------------|
| Metrics | Prometheus, pushed over OTLP every 10 s | Grafana → Explore → **Prometheus** |
| Traces | Tempo, pushed over OTLP | Grafana → Explore → **Tempo** |
| Logs | The gateway's standard output only | `docker compose logs payment_gateway` |

The gateway does not send logs to Loki, so the Loki data source in Grafana will be empty. Logs are
read from the container, and each line carries the `traceId` that links it to a trace.

Set the time range (top right) to **Last 15 minutes** or less, and keep sending traffic while you
look. `rate()` queries have nothing to show without recent requests.

### Metrics

Three ways to query, from most to least convenient:

1. **Grafana → Explore → Prometheus.** Paste a query from [Useful queries](#metrics) and change
   `_seconds_` to `_milliseconds_` in the latency ones. Use the "Builder" tab to browse metric
   names, or type `payments_` and let it autocomplete.
2. **The raw scrape** shows exactly what the app exposes right now, with `_seconds_` names:
   ```bash
   curl -s localhost:8090/actuator/prometheus | grep -E '^(payments_|http_client_requests_seconds_count)'
   ```
3. **Prometheus's own API**, to check what Grafana can see without the UI:
   ```bash
   docker exec lgtm curl -s -G localhost:9090/api/v1/query \
     --data-urlencode 'query=sum by (status) (payments_processed_total)'
   ```

Quick questions and the query that answers them (Grafana names):

| Question | Query |
|----------|-------|
| How many payments of each outcome so far? | `sum by (status) (payments_processed_total)` |
| Is the bank failing right now? | `rate(payments_bank_failures_total[5m])` |
| What is the bank answering? | `sum by (status) (rate(http_client_requests_milliseconds_count{uri="/payments"}[5m]))` |
| How slow is the bank (p95, ms)? | `histogram_quantile(0.95, sum by (le) (rate(http_client_requests_milliseconds_bucket{uri="/payments"}[5m])))` |
| How slow is the gateway (p99, ms)? | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_milliseconds_bucket{uri="/v1/payments"}[5m])))` |
| Which status codes are merchants getting? | `sum by (status) (rate(http_server_requests_milliseconds_count{uri="/v1/payments"}[5m]))` |
| Are there any `502`/`504`? | `sum by (status) (increase(http_server_requests_milliseconds_count{status=~"502\|504"}[15m]))` |

Labels worth knowing on the HTTP metrics: `uri` (`/v1/payments` for the gateway, `/payments` for
the bank), `status`, `method`, `outcome` (`SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR`) and
`exception`. Health checks and scrapes under `/actuator` are not recorded.

### Traces

In Grafana → Explore → **Tempo**, choose the **TraceQL** query type. A payment is one trace: a
server span `http post /v1/payments` and a child client span `http post` for the bank call.
Span attributes you can filter on: `uri`, `status`, `method`, `outcome`, `client.name`.

| Question | TraceQL |
|----------|---------|
| All payment requests | `{ resource.service.name = "payment-gateway" && name = "http post /v1/payments" }` |
| Requests that ended in a 502 | `{ span.status = "502" }` |
| Server errors | `{ span.uri = "/v1/payments" && span.outcome = "SERVER_ERROR" }` |
| Slow calls to the bank | `{ resource.service.name = "payment-gateway" && kind = client && duration > 2s }` |
| One merchant-reported request | Paste the `X-Trace-Id` into the search box ("TraceID" query type) |

Open a trace to see how the time splits between the gateway and the bank call. A long client span
means the bank is slow; a long server span with a short client span means the time is spent in the
gateway.

Without the UI:
```bash
docker exec lgtm curl -s localhost:3200/api/traces/<trace id>
```

### Logs

```bash
docker compose logs --since 15m payment_gateway                      # recent logs
docker compose logs payment_gateway | grep '"traceId":"<trace id>"'   # one request
docker compose logs payment_gateway | grep '"message":"Payment processed"'
docker compose logs payment_gateway | grep -E '"level":"(WARN|ERROR)"'
```

In Docker the logs are JSON (one object per line), so `jq` works if you have it:
```bash
docker compose logs --no-log-prefix payment_gateway \
  | jq -r 'select(.message=="Payment processed") | [."@timestamp", .payment_id, .payment_status, .amount, .currency] | @tsv'
```

Running locally with `./gradlew bootRun`, logs are plain text, but `traceId` and `spanId` are still
in each line.

### From a merchant report to the full story

1. Get the **`X-Trace-Id`** from the merchant (every payment response has it).
2. **Traces:** search Tempo for that id to see timings and the bank call's status.
3. **Logs:** grep the container logs for the same id to see what the gateway logged.
4. **Metrics:** check whether it is one request or a trend, using the queries above.

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
merchant receives the full list of invalid fields in the `400` response: wrong types and failed
rules together, one entry per problem.

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
2. Ask the merchant for the `400` body, or reproduce with their request (minus the real card
   number) using [requests.http](../requests.http). The body lists every invalid field, and the
   messages say what to fix:

   | Message | Meaning |
   |---------|---------|
   | `<field>: must be a string` | A text field (`card_number`, `currency`, `cvv`) was sent as a JSON number. Send it in quotes |
   | `<field>: must be a whole number` | `expiry_month`, `expiry_year` or `amount` was text or a decimal (`10.5`). Amounts are integers in minor units |
   | `<field>: must not be null` / `must not be blank` | The field is missing or empty |
   | `The request body is not valid JSON` | Malformed JSON, often a bad serializer or a wrong `Content-Type` |
   | `The request body is missing` | Empty body |
   | `body: must be a JSON object` | The body was an array or a bare value |
   | Anything else | A rule from [DESIGN.md](DESIGN.md#validation-rules-and-assumptions), for example `card has expired` |
3. If **every** merchant is affected at once, suspect a gateway change. A common one is the
   expiry check at a month boundary: a card expiring this month is valid until the month ends, in
   UTC. Roll back if a recent deployment lines up.

### Merchants get 404 for a payment they created

1. **Has the gateway restarted?** Payments are in memory, so any restart, crash or rollback loses
   all of them. Check the container's start time and uptime.
2. **Is more than one instance running?** Each has its own payments. Scale to exactly one.
3. Otherwise, check the id: an unknown id is a `404`, a malformed one is a `400`.

### Grafana shows no data

Check in this order:

1. **Was the gateway started with both compose files?** This is the usual cause.
   ```bash
   docker inspect payment_gateway --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
   ```
   It must list `docker-compose.observability.yml`. If it doesn't, recreate the gateway:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
   ```
   (This restarts the gateway, so stored payments are lost.)
2. **Is there recent traffic?** Send a few payments, wait about 30 seconds (metrics are pushed every
   10 s), and set the Grafana time range to the last 15 minutes. `rate()` queries are empty without
   recent requests.
3. **Is the metric name right?** In Grafana the latency metrics end in `_milliseconds_`, not
   `_seconds_` ([see Metrics](#metrics)). A query with the wrong name returns "No data".
4. **Is Grafana receiving anything?** Ask its Prometheus directly:
   ```bash
   docker exec lgtm curl -s -G localhost:9090/api/v1/query --data-urlencode 'query=payments_processed_total'
   ```
   An empty `result` with a fresh gateway means nothing is arriving: go back to step 1. For traces,
   use Explore → Tempo and search for the service `payment-gateway`.
5. **Is the gateway itself producing metrics?** `curl localhost:8090/actuator/prometheus | grep payments_`.
   If that has data, the app is fine and the problem is the push or the query.

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
