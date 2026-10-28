package com.checkout.payment.gateway.client;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BankPropertiesTest {

  @Test
  void acceptsAUrlWithAValidHost() {
    assertThatNoException().isThrownBy(() -> properties("http://bank-simulator:8080"));
  }

  @Test
  void failsFastOnAHostJavaCannotParse() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> properties("http://bank_simulator:8080"))
        .withMessageContaining("bank.base-url has no valid host");
  }

  private static BankProperties properties(String baseUrl) {
    return new BankProperties(URI.create(baseUrl), Duration.ofSeconds(1), Duration.ofSeconds(1));
  }
}
