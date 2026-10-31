package com.checkout.payment.gateway.client;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the acquiring bank lives and how long to wait for it.
 *
 * @param baseUrl         the bank's base URL, which must include a host
 * @param connectTimeout  how long to wait to open a connection to the bank
 * @param responseTimeout how long to wait for the bank's answer before treating the outcome as
 *                        unknown
 */
@Validated
@ConfigurationProperties("bank")
public record BankProperties(
    @NotNull URI baseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration responseTimeout) {

  /**
   * Rejects a base URL without a host, which would otherwise only fail on the first payment.
   *
   * @throws IllegalArgumentException if {@code baseUrl} has no host
   */
  public BankProperties {
    if (baseUrl != null && baseUrl.getHost() == null) {
      throw new IllegalArgumentException("bank.base-url has no valid host: " + baseUrl);
    }
  }
}
