package com.checkout.payment.gateway.model;

import static com.checkout.payment.gateway.TestPayments.request;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks every rule in the brief's validation table, at its boundaries. "Today" is 15 Jun 2026.
 */
@DisplayName("Payment request validation")
class PostPaymentRequestValidationTest {

  private static final Clock TODAY =
      Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC);

  private static ValidatorFactory factory;
  private static Validator validator;

  /**
   * Builds a validator whose clock is fixed at 15 Jun 2026.
   */
  @BeforeAll
  static void createValidator() {
    factory = Validation.byDefaultProvider().configure()
        .clockProvider(() -> TODAY)
        .buildValidatorFactory();
    validator = factory.getValidator();
  }

  /**
   * Releases the validator factory.
   */
  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  @DisplayName("Accepts a valid request")
  @Test
  void acceptsAValidRequest() {
    assertThat(violatedFields(request())).isEmpty();
  }

  @Nested
  @DisplayName("Card number")
  class CardNumber {

    @ParameterizedTest
    @ValueSource(strings = {"12345678901234", "1234567890123456789"})
    void acceptsBetween14And19Digits(String cardNumber) {
      assertThat(violatedFields(withCardNumber(cardNumber))).isEmpty();
    }

    @DisplayName("Rejects missing or malformed")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234567890123", "12345678901234567890", "2222 4053 4324 8877",
        "222240534324887a", "-222405343248877"})
    void rejectsMissingOrMalformed(String cardNumber) {
      assertThat(violatedFields(withCardNumber(cardNumber))).containsOnly("cardNumber");
    }

    /**
     * A valid request with the given card number.
     *
     * @param cardNumber the card number to use
     * @return the request
     */
    private PostPaymentRequest withCardNumber(String cardNumber) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(cardNumber, r.expiryMonth(), r.expiryYear(), r.currency(),
          r.amount(), r.cvv());
    }
  }

  @Nested
  @DisplayName("Expiry date")
  class Expiry {

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({"6, 2026", "7, 2026", "1, 2027", "12, 2099"})
    void acceptsTheCurrentMonthOrLater(int month, int year) {
      assertThat(violatedFields(withExpiry(month, year))).isEmpty();
    }

    @DisplayName("Rejects a past month against the year")
    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({"5, 2026", "12, 2025", "6, 2025"})
    void rejectsAPastMonthAgainstTheYear(int month, int year) {
      assertThat(violations(withExpiry(month, year)))
          .containsExactly("expiryYear: card has expired");
    }

    @DisplayName("Rejects a month outside 1 to 12 without also reporting expiry")
    @ParameterizedTest
    @ValueSource(ints = {0, 13, -1})
    void rejectsAMonthOutside1To12WithoutAlsoReportingExpiry(int month) {
      assertThat(violatedFields(withExpiry(month, 2099))).containsOnly("expiryMonth");
    }

    @DisplayName("Rejects missing month and year")
    @Test
    void rejectsMissingMonthAndYear() {
      assertThat(violatedFields(withExpiry(null, null)))
          .containsExactlyInAnyOrder("expiryMonth", "expiryYear");
    }

    /**
     * A valid request with the given expiry date.
     *
     * @param month the expiry month
     * @param year  the expiry year
     * @return the request
     */
    private PostPaymentRequest withExpiry(Integer month, Integer year) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(r.cardNumber(), month, year, r.currency(), r.amount(), r.cvv());
    }
  }

  @Nested
  @DisplayName("Currency")
  class Currency {

    @ParameterizedTest
    @ValueSource(strings = {"USD", "GBP", "EUR"})
    void acceptsSupportedCurrencies(String currency) {
      assertThat(violatedFields(withCurrency(currency))).isEmpty();
    }

    @DisplayName("Rejects missing or unsupported")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"JPY", "gbp", "GB", "GBPP"})
    void rejectsMissingOrUnsupported(String currency) {
      assertThat(violatedFields(withCurrency(currency))).containsOnly("currency");
    }

    /**
     * A valid request with the given currency.
     *
     * @param currency the currency to use
     * @return the request
     */
    private PostPaymentRequest withCurrency(String currency) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(r.cardNumber(), r.expiryMonth(), r.expiryYear(), currency,
          r.amount(), r.cvv());
    }
  }

  @Nested
  @DisplayName("Amount")
  class Amount {

    @ParameterizedTest
    @ValueSource(longs = {1, Long.MAX_VALUE})
    void acceptsPositiveAmounts(long amount) {
      assertThat(violatedFields(withAmount(amount))).isEmpty();
    }

    @DisplayName("Rejects zero or negative")
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsZeroOrNegative(long amount) {
      assertThat(violatedFields(withAmount(amount))).containsOnly("amount");
    }

    @DisplayName("Rejects missing")
    @Test
    void rejectsMissing() {
      assertThat(violatedFields(withAmount(null))).containsOnly("amount");
    }

    /**
     * A valid request with the given amount.
     *
     * @param amount the amount to use
     * @return the request
     */
    private PostPaymentRequest withAmount(Long amount) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(r.cardNumber(), r.expiryMonth(), r.expiryYear(), r.currency(),
          amount, r.cvv());
    }
  }

  @Nested
  @DisplayName("CVV")
  class Cvv {

    @ParameterizedTest
    @ValueSource(strings = {"123", "1234", "000"})
    void accepts3Or4Digits(String cvv) {
      assertThat(violatedFields(withCvv(cvv))).isEmpty();
    }

    @DisplayName("Rejects missing or malformed")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12", "12345", "12a", " 123"})
    void rejectsMissingOrMalformed(String cvv) {
      assertThat(violatedFields(withCvv(cvv))).containsOnly("cvv");
    }

    /**
     * A valid request with the given CVV.
     *
     * @param cvv the CVV to use
     * @return the request
     */
    private PostPaymentRequest withCvv(String cvv) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(r.cardNumber(), r.expiryMonth(), r.expiryYear(), r.currency(),
          r.amount(), cvv);
    }
  }

  @DisplayName("Never prints the card number or CVV")
  @Test
  void neverPrintsTheCardNumberOrCvv() {
    assertThat(request().toString())
        .doesNotContain(request().cardNumber())
        .doesNotContain("cvv=" + request().cvv());
  }

  /**
   * The names of the fields that fail validation.
   *
   * @param request the request to validate
   * @return the property paths of the violations
   */
  private static List<String> violatedFields(PostPaymentRequest request) {
    return validator.validate(request).stream()
        .map(violation -> violation.getPropertyPath().toString())
        .toList();
  }

  /**
   * Each violation as {@code field: message}.
   *
   * @param request the request to validate
   * @return the described violations
   */
  private static List<String> violations(PostPaymentRequest request) {
    return validator.validate(request).stream()
        .map(PostPaymentRequestValidationTest::describe)
        .toList();
  }

  /**
   * Formats one violation as {@code field: message}.
   *
   * @param violation the violation
   * @return the description
   */
  private static String describe(ConstraintViolation<PostPaymentRequest> violation) {
    return violation.getPropertyPath() + ": " + violation.getMessage();
  }
}
