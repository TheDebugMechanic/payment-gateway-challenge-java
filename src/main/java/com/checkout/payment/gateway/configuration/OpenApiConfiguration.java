package com.checkout.payment.gateway.configuration;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI documentation settings.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

  /**
   * Springdoc builds schemas with its own (Jackson 2) mapper, which doesn't see the application's
   * snake_case setting. Without this, the docs would say {@code cardNumber} while the API expects
   * {@code card_number}.
   *
   * @return a model resolver that names schema properties in snake_case
   */
  @Bean
  public ModelResolver snakeCaseModelResolver() {
    return new ModelResolver(Json.mapper().copy()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
  }
}
