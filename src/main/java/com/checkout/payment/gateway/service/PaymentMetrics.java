package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.enums.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Business metrics. Bank latency and error rates come from Spring's built-in
 * {@code http.client.requests} metric, so they are not duplicated here.
 */
@Component
public class PaymentMetrics {

  private final Map<PaymentStatus, Counter> outcomes = new EnumMap<>(PaymentStatus.class);
  private final Counter bankFailures;

  public PaymentMetrics(MeterRegistry registry) {
    for (PaymentStatus status : PaymentStatus.values()) {
      outcomes.put(status, Counter.builder("payments.processed")
          .description("Payment requests by outcome")
          .tag("status", status.name().toLowerCase(Locale.ROOT))
          .register(registry));
    }
    bankFailures = Counter.builder("payments.bank.failures")
        .description("Payment requests that failed because the acquiring bank errored or timed out")
        .register(registry);
  }

  public void recordOutcome(PaymentStatus status) {
    outcomes.get(status).increment();
  }

  public void recordBankFailure() {
    bankFailures.increment();
  }
}
