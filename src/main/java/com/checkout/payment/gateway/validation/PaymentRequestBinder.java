package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.exception.FieldError;
import com.checkout.payment.gateway.exception.InvalidPaymentRequestException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Turns a payment request's JSON into a {@link PostPaymentRequest} and validates it, reporting
 * every problem at once. Binding straight to the record would stop at the first field of the wrong
 * type (for example {@code "amount": 10.5}) and never check the others.
 *
 * <p>A field of the wrong type is reported as such and left {@code null}; the remaining fields are
 * validated as usual.
 */
@Component
public class PaymentRequestBinder {

  private final Validator validator;

  /**
   * Creates the binder.
   *
   * @param validator checks the bound request against its constraints
   */
  public PaymentRequestBinder(Validator validator) {
    this.validator = validator;
  }

  /**
   * Reads and validates a payment request.
   *
   * @param body the request body
   * @return the valid request
   * @throws InvalidPaymentRequestException listing every field that has the wrong type or fails
   *                                        validation
   */
  public PostPaymentRequest bind(JsonNode body) {
    if (!body.isObject()) {
      throw new InvalidPaymentRequestException(
          List.of(new FieldError("body", "must be a JSON object")));
    }
    List<FieldError> errors = new ArrayList<>();
    PostPaymentRequest request = new PostPaymentRequest(
        string(body, "card_number", errors),
        integer(body, "expiry_month", errors),
        integer(body, "expiry_year", errors),
        string(body, "currency", errors),
        wholeNumber(body, "amount", errors),
        string(body, "cvv", errors));
    Set<String> wrongType = errors.stream().map(FieldError::field).collect(Collectors.toSet());
    validator.validate(request).forEach(violation -> {
      String field = toSnakeCase(violation.getPropertyPath().toString());
      // A field of the wrong type is null here, so "must not be null" would only confuse.
      if (!wrongType.contains(field)) {
        errors.add(new FieldError(field, violation.getMessage()));
      }
    });
    if (!errors.isEmpty()) {
      throw new InvalidPaymentRequestException(errors);
    }
    return request;
  }

  /**
   * Reads a text field. Numbers are not accepted, so a CVV such as {@code 012} can't lose its
   * leading zero.
   *
   * @param body   the request body
   * @param field  the JSON name of the field
   * @param errors collects a wrong-type error
   * @return the text, or {@code null} when the field is missing, null or the wrong type
   */
  private static String string(JsonNode body, String field, List<FieldError> errors) {
    JsonNode node = body.get(field);
    if (isAbsent(node)) {
      return null;
    }
    if (!node.isString()) {
      errors.add(new FieldError(field, "must be a string"));
      return null;
    }
    return node.stringValue();
  }

  /**
   * Reads a whole-number field that fits an {@code int}.
   *
   * @param body   the request body
   * @param field  the JSON name of the field
   * @param errors collects a wrong-type error
   * @return the number, or {@code null} when the field is missing, null or the wrong type
   */
  private static Integer integer(JsonNode body, String field, List<FieldError> errors) {
    JsonNode node = body.get(field);
    if (isAbsent(node)) {
      return null;
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      errors.add(new FieldError(field, "must be a whole number"));
      return null;
    }
    return node.intValue();
  }

  /**
   * Reads a whole-number field that fits a {@code long}. {@code 10.5} and {@code 10.0} are
   * rejected, never truncated.
   *
   * @param body   the request body
   * @param field  the JSON name of the field
   * @param errors collects a wrong-type error
   * @return the number, or {@code null} when the field is missing, null or the wrong type
   */
  private static Long wholeNumber(JsonNode body, String field, List<FieldError> errors) {
    JsonNode node = body.get(field);
    if (isAbsent(node)) {
      return null;
    }
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      errors.add(new FieldError(field, "must be a whole number"));
      return null;
    }
    return node.longValue();
  }

  /**
   * Whether a field is missing or an explicit JSON null, both of which validation reports.
   *
   * @param node the field's value, or {@code null} when the field is missing
   * @return {@code true} when there is no value
   */
  private static boolean isAbsent(JsonNode node) {
    return node == null || node.isNull();
  }

  /**
   * Reports fields by their JSON names (expiry_month), not their Java names (expiryMonth).
   *
   * @param field the Java field name
   * @return the field name in snake_case
   */
  private static String toSnakeCase(String field) {
    return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }
}
