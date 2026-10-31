package com.checkout.payment.gateway.configuration;

import java.util.Objects;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * Observability settings.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

  /**
   * Health probes and metric scrapes hit /actuator every few seconds. Observing them would bury
   * payment traces in noise and skew the HTTP latency metrics.
   *
   * @return a predicate that skips observations of requests under {@code /actuator}
   */
  @Bean
  public ObservationPredicate ignoreActuatorRequests() {
    return (name, context) -> !(context instanceof ServerRequestObservationContext request
        && Objects.requireNonNull(request.getCarrier()).getPath().value().startsWith("/actuator"));
  }
}
