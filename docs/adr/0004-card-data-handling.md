# ADR-0004: Card data handling

**Status:** Accepted

## Context

The card number (PAN) and CVV are the most sensitive data a gateway handles. Under PCI DSS the
CVV must never be stored after authorization, and the PAN must be protected wherever it is kept,
including logs. A gateway also must never return a full card number.

## Decision

Card data is kept to the narrowest possible path: **request → validation → bank call**, and
nowhere else.

- **Stored record:** `Payment` has a field for the last four digits and no field for the PAN or
  the CVV, so the types themselves make storing them impossible by mistake.
- **Responses:** `PaymentResponse` has the last four digits only. Error responses never echo the
  request. Controller tests check that the card number doesn't appear in a
  successful response or an error response.
- **Logs:** the types that carry the PAN and CVV (`PostPaymentRequest`, `PaymentCommand`,
  `BankPaymentRequest`) override `toString()` to mask both, so an accidental `log.info("{}",
  request)` stays safe. The outcome log line has `card_last_four` only.
- **Caching:** payment responses carry `Cache-Control: no-store`, so browsers, proxies and CDNs
  don't keep them.

## Consequences

- Reviewing a change for card data leaks comes down to three types.
- **Not addressed yet**, because they're about how the service is deployed rather than the
  service itself:
  - **TLS.** Docker compose and the simulator use plain HTTP. In production both the merchant
    connection and the bank connection must use TLS.
  - **Tokenization.** In a real system the gateway wouldn't hold raw PANs at all: a vault would
    swap the card number for a token at the edge, and only the component that talks to the bank
    would turn it back. That removes the gateway from most of the PCI scope.
  - **Memory.** The PAN and CVV are Java `String`s, which can't be wiped and may outlive the
    request until garbage collection. That's normal for JVM services and is mitigated by the
    above.
