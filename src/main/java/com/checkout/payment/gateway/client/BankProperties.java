package com.checkout.payment.gateway.client;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the acquiring bank lives and how long to wait for it.
 *
 * @param responseTimeout how long to wait for the bank's answer before treating the outcome as
 *                        unknown
 */
@Validated
@ConfigurationProperties("bank")
public record BankProperties(
    @NotNull URI baseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration responseTimeout) {

  public BankProperties {
    // java.net.URI leaves the host null for names it can't parse (e.g. "bank_simulator", since
    // underscores aren't legal in hostnames). Fail at startup instead of on the first payment.
    if (baseUrl != null && baseUrl.getHost() == null) {
      throw new IllegalArgumentException("bank.base-url has no valid host: " + baseUrl);
    }
  }
}
