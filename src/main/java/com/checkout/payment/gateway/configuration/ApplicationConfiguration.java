package com.checkout.payment.gateway.configuration;

import java.time.Clock;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Lets validators that compare against "now", such as card expiry, use the {@link Clock} bean.
   */
  @Bean
  public ValidationConfigurationCustomizer validationClock(Clock clock) {
    return configuration -> configuration.clockProvider(() -> clock);
  }
}
