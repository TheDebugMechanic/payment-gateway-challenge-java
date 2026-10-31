package com.checkout.payment.gateway.validation;

import static com.checkout.payment.gateway.TestPayments.AUTHORIZED_CARD;
import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.exception.FieldError;
import com.checkout.payment.gateway.exception.InvalidPaymentRequestException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turning JSON into a validated request: wrong types and failed rules are reported together.
 * "Today" is 15 Jun 2026.
 */
@DisplayName("Payment request binder")
class PaymentRequestBinderTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String VALID = """
      {"card_number": "%s", "expiry_month": 4, "expiry_year": 2099,
       "currency": "GBP", "amount": 1050, "cvv": "123"}""".formatted(AUTHORIZED_CARD);

  private static ValidatorFactory factory;
  private static PaymentRequestBinder binder;

  /**
   * Builds a binder whose validator clock is fixed at 15 Jun 2026.
   */
  @BeforeAll
  static void createBinder() {
    factory = Validation.byDefaultProvider().configure()
        .clockProvider(() -> Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC))
        .buildValidatorFactory();
    binder = new PaymentRequestBinder(factory.getValidator());
  }

  /**
   * Releases the validator factory.
   */
  @AfterAll
  static void closeFactory() {
    factory.close();
  }

  @DisplayName("Binds a valid request")
  @Test
  void bindsAValidRequest() {
    PostPaymentRequest request = binder.bind(json(VALID));

    assertThat(request).isEqualTo(
        new PostPaymentRequest(AUTHORIZED_CARD, 4, 2099, "GBP", 1050L, "123"));
  }

  @DisplayName("Keeps the leading zero of a CVV")
  @Test
  void keepsTheLeadingZeroOfACvv() {
    assertThat(binder.bind(json(VALID.replace("\"123\"", "\"012\""))).cvv()).isEqualTo("012");
  }

  @DisplayName("Reports a decimal amount, however it is written")
  @ParameterizedTest
  @ValueSource(strings = {"10.5", "10.0", "1e1"})
  void reportsADecimalAmount(String amount) {
    assertThat(errorsFor(VALID.replace("1050", amount)))
        .containsExactly(new FieldError("amount", "must be a whole number"));
  }

  @DisplayName("Reports a whole number that is too large")
  @Test
  void reportsAWholeNumberThatIsTooLarge() {
    assertThat(errorsFor(VALID.replace("1050", "9223372036854775808")))
        .containsExactly(new FieldError("amount", "must be a whole number"));
    assertThat(errorsFor(VALID.replace("\"expiry_month\": 4", "\"expiry_month\": 4294967296")))
        .containsExactly(new FieldError("expiry_month", "must be a whole number"));
  }

  @DisplayName("Reports a number where a string is expected")
  @Test
  void reportsANumberWhereAStringIsExpected() {
    assertThat(errorsFor(VALID.replace("\"123\"", "123")))
        .containsExactly(new FieldError("cvv", "must be a string"));
  }

  @DisplayName("Reports text where a whole number is expected")
  @Test
  void reportsTextWhereAWholeNumberIsExpected() {
    assertThat(errorsFor(VALID.replace("\"expiry_month\": 4", "\"expiry_month\": \"April\"")))
        .containsExactly(new FieldError("expiry_month", "must be a whole number"));
  }

  @DisplayName("Reports a missing field and an explicit null as must not be null")
  @Test
  void reportsAMissingFieldAndANullAsMustNotBeNull() {
    assertThat(errorsFor(VALID.replace("\"currency\": \"GBP\",", "")))
        .contains(new FieldError("currency", "must not be blank"));
    assertThat(errorsFor(VALID.replace("1050", "null")))
        .containsExactly(new FieldError("amount", "must not be null"));
  }

  @DisplayName("Does not also report must not be null for a wrongly typed field")
  @Test
  void doesNotAlsoReportMustNotBeNullForAWronglyTypedField() {
    assertThat(errorsFor(VALID.replace("1050", "\"ten\"")))
        .extracting(FieldError::field).containsExactly("amount");
  }

  @DisplayName("Reports wrongly typed fields together with failed rules")
  @Test
  void reportsWronglyTypedFieldsTogetherWithFailedRules() {
    List<FieldError> errors = errorsFor("""
        {"card_number": 1, "expiry_month": 4, "expiry_year": 2020,
         "currency": "JPY", "amount": 10.5, "cvv": "1"}""");

    assertThat(errors).extracting(FieldError::field)
        .containsExactlyInAnyOrder("card_number", "expiry_year", "currency", "amount", "cvv");
  }

  @DisplayName("Skips the expiry rule when the year is wrongly typed")
  @Test
  void skipsTheExpiryRuleWhenAFieldIsWronglyTyped() {
    assertThat(errorsFor(VALID.replace("\"expiry_year\": 2099", "\"expiry_year\": \"soon\"")))
        .containsExactly(new FieldError("expiry_year", "must be a whole number"));
  }

  @DisplayName("Rejects a body that is not a JSON object")
  @ParameterizedTest
  @ValueSource(strings = {"[]", "42", "\"text\"", "null"})
  void rejectsABodyThatIsNotAnObject(String body) {
    assertThat(errorsFor(body)).containsExactly(new FieldError("body", "must be a JSON object"));
  }

  /**
   * Binds a body that is expected to be invalid.
   *
   * @param body the raw JSON
   * @return the errors the binder reported
   */
  private static List<FieldError> errorsFor(String body) {
    InvalidPaymentRequestException thrown = null;
    try {
      binder.bind(json(body));
    } catch (InvalidPaymentRequestException e) {
      thrown = e;
    }
    assertThat(thrown).as("binder should have rejected %s", body).isNotNull();
    return thrown.errors();
  }

  /**
   * Parses JSON.
   *
   * @param body the raw JSON
   * @return the parsed tree
   */
  private static JsonNode json(String body) {
    return MAPPER.readTree(body);
  }
}
