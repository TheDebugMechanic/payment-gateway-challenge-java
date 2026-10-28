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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks every rule in the brief's validation table, at its boundaries. "Today" is 15 Jun 2026.
 */
class PostPaymentRequestValidationTest {

  private static final Clock TODAY =
      Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC);

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void createValidator() {
    factory = Validation.byDefaultProvider().configure()
        .clockProvider(() -> TODAY)
        .buildValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  @Test
  void acceptsAValidRequest() {
    assertThat(violatedFields(request())).isEmpty();
  }

  @Nested
  class CardNumber {

    @ParameterizedTest
    @ValueSource(strings = {"12345678901234", "1234567890123456789"})
    void acceptsBetween14And19Digits(String cardNumber) {
      assertThat(violatedFields(withCardNumber(cardNumber))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234567890123", "12345678901234567890", "2222 4053 4324 8877",
        "222240534324887a", "-222405343248877"})
    void rejectsMissingOrMalformed(String cardNumber) {
      assertThat(violatedFields(withCardNumber(cardNumber))).containsOnly("cardNumber");
    }

    private PostPaymentRequest withCardNumber(String cardNumber) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(cardNumber, r.expiryMonth(), r.expiryYear(), r.currency(),
          r.amount(), r.cvv());
    }
  }

  @Nested
  class Expiry {

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({"6, 2026", "7, 2026", "1, 2027", "12, 2099"})
    void acceptsTheCurrentMonthOrLater(int month, int year) {
      assertThat(violatedFields(withExpiry(month, year))).isEmpty();
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({"5, 2026", "12, 2025", "6, 2025"})
    void rejectsAPastMonthAgainstTheYear(int month, int year) {
      assertThat(violations(withExpiry(month, year)))
          .containsExactly("expiryYear: card has expired");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13, -1})
    void rejectsAMonthOutside1To12WithoutAlsoReportingExpiry(int month) {
      assertThat(violatedFields(withExpiry(month, 2099))).containsOnly("expiryMonth");
    }

    @Test
    void rejectsMissingMonthAndYear() {
      assertThat(violatedFields(withExpiry(null, null)))
          .containsExactlyInAnyOrder("expiryMonth", "expiryYear");
    }

    private PostPaymentRequest withExpiry(Integer month, Integer year) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(r.cardNumber(), month, year, r.currency(), r.amount(), r.cvv());
    }
  }

  @Nested
  class Currency {

    @ParameterizedTest
    @ValueSource(strings = {"USD", "GBP", "EUR"})
    void acceptsSupportedCurrencies(String currency) {
      assertThat(violatedFields(withCurrency(currency))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"JPY", "gbp", "GB", "GBPP"})
    void rejectsMissingOrUnsupported(String currency) {
      assertThat(violatedFields(withCurrency(currency))).containsOnly("currency");
    }

    private PostPaymentRequest withCurrency(String currency) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(r.cardNumber(), r.expiryMonth(), r.expiryYear(), currency,
          r.amount(), r.cvv());
    }
  }

  @Nested
  class Amount {

    @ParameterizedTest
    @ValueSource(longs = {1, Long.MAX_VALUE})
    void acceptsPositiveAmounts(long amount) {
      assertThat(violatedFields(withAmount(amount))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsZeroOrNegative(long amount) {
      assertThat(violatedFields(withAmount(amount))).containsOnly("amount");
    }

    @Test
    void rejectsMissing() {
      assertThat(violatedFields(withAmount(null))).containsOnly("amount");
    }

    private PostPaymentRequest withAmount(Long amount) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(r.cardNumber(), r.expiryMonth(), r.expiryYear(), r.currency(),
          amount, r.cvv());
    }
  }

  @Nested
  class Cvv {

    @ParameterizedTest
    @ValueSource(strings = {"123", "1234", "000"})
    void accepts3Or4Digits(String cvv) {
      assertThat(violatedFields(withCvv(cvv))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12", "12345", "12a", " 123"})
    void rejectsMissingOrMalformed(String cvv) {
      assertThat(violatedFields(withCvv(cvv))).containsOnly("cvv");
    }

    private PostPaymentRequest withCvv(String cvv) {
      PostPaymentRequest r = request();
      return new PostPaymentRequest(r.cardNumber(), r.expiryMonth(), r.expiryYear(), r.currency(),
          r.amount(), cvv);
    }
  }

  @Test
  void neverPrintsTheCardNumberOrCvv() {
    assertThat(request().toString())
        .doesNotContain(request().cardNumber())
        .doesNotContain("cvv=" + request().cvv());
  }

  private static List<String> violatedFields(PostPaymentRequest request) {
    return validator.validate(request).stream()
        .map(violation -> violation.getPropertyPath().toString())
        .toList();
  }

  private static List<String> violations(PostPaymentRequest request) {
    return validator.validate(request).stream()
        .map(PostPaymentRequestValidationTest::describe)
        .toList();
  }

  private static String describe(ConstraintViolation<PostPaymentRequest> violation) {
    return violation.getPropertyPath() + ": " + violation.getMessage();
  }
}
