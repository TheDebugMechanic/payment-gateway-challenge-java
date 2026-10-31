package com.checkout.payment.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the payment gateway.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentGatewayApplication {

  /**
   * Starts the Spring Boot application.
   *
   * @param args command-line arguments passed to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(PaymentGatewayApplication.class, args);
  }
}
