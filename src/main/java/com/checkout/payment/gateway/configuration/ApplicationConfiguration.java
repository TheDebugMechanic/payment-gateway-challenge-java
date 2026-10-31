package com.checkout.payment.gateway.configuration;

import java.time.Clock;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * General application beans.
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {

  /**
   * The application's source of time, in UTC. Tests replace it with a fixed clock.
   *
   * @return the system clock in UTC
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Lets validators that compare against "now", such as card expiry, use the {@link Clock} bean.
   *
   * @param clock the application clock
   * @return a customizer that makes the validator factory use {@code clock}
   */
  @Bean
  public ValidationConfigurationCustomizer validationClock(Clock clock) {
    return configuration -> configuration.clockProvider(() -> clock);
  }
}
