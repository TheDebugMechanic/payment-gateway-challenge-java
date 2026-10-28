package com.checkout.payment.gateway.configuration;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

  /**
   * Health probes and metric scrapes hit /actuator every few seconds. Observing them would bury
   * payment traces in noise and skew the HTTP latency metrics.
   */
  @Bean
  public ObservationPredicate ignoreActuatorRequests() {
    return (name, context) -> !(context instanceof ServerRequestObservationContext request
        && request.getCarrier().getPath().value().startsWith("/actuator"));
  }
}
