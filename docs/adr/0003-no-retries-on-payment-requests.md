# ADR-0003: Never retry a payment request; tell the merchant whether money may have moved

**Status:** Accepted

## Context

Calls to the bank fail in two different ways:

- **The bank answers with an error** (the simulator's `503`), or can't be reached at all. The
  payment wasn't processed.
- **The bank doesn't answer in time.** The request may have reached the bank and been
  authorized, with only the response lost. The outcome is **unknown**.

Retrying is the usual reaction to both. For a payment it's dangerous: the bank call isn't
idempotent, so a retry after a lost response can charge the shopper twice.

## Decision

1. **The gateway never retries a payment request to the bank.** One request is sent, and
   `AcquiringBankClientTest` checks that a failed request was sent exactly once.
2. **The two failures get different responses:**
   - Bank error or bank unreachable: `502 Bad Gateway`. *"No payment was made; it is safe to try
     again."*
   - Bank timeout (no response within 10 s): `504 Gateway Timeout`. *"The payment outcome is
     unknown. Trying again may charge the shopper twice."*
3. Nothing is stored in either case, and both are counted in `payments_bank_failures_total`.

## Consequences

- Merchants get an honest answer they can act on. The `502` vs `504` distinction tells them
  whether a retry is safe.
- After a `504` there's no safe way for a merchant to finish the payment. Today they'd have to
  check with support. That's acceptable for this phase, and it's the main reason idempotency is
  the first item of future work.
- **Next step: idempotency keys.** Merchants send an `Idempotency-Key` header with each POST. The
  gateway stores the response per merchant and key, with a TTL, and replays it for a repeat
  request instead of calling the bank again. The gateway would also pass its own key to the
  bank, so a retry after a timeout returns the original authorization rather than creating a
  new one. Retries then become safe, both for merchants and inside the gateway.
- Timeouts are configurable (`bank.connect-timeout`, `bank.response-timeout`). A short connect
  timeout (2 s) fails fast when the bank is down. The longer response timeout (10 s) allows for
  a slow bank, because a timeout is the more expensive failure: it leaves the outcome unknown.
