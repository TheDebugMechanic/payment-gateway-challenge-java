package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.service.PaymentMetrics;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Turns failures into RFC 9457 problem details.
 */
@RestControllerAdvice
public class CommonExceptionHandler {

  private final PaymentMetrics metrics;

  public CommonExceptionHandler(PaymentMetrics metrics) {
    this.metrics = metrics;
  }

  /**
   * A field failed validation.
   */
  @ExceptionHandler(WebExchangeBindException.class)
  public ProblemDetail handleInvalidRequest(WebExchangeBindException ex) {
    List<FieldError> errors = ex.getFieldErrors().stream()
        .map(error -> new FieldError(toSnakeCase(error.getField()),
            error.getDefaultMessage()))
        .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
        .toList();
    ProblemDetail problem = rejected("One or more fields are invalid");
    problem.setProperty("errors", errors);
    return problem;
  }

  /**
   * The body is missing or isn't valid JSON, or a path variable has the wrong type.
   */
  @ExceptionHandler(ServerWebInputException.class)
  public ProblemDetail handleUnreadableRequest(ServerWebInputException ex,
      ServerWebExchange exchange) {
    if (HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
      return rejected("The request body is missing or is not valid JSON of the expected shape");
    }
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getReason());
  }

  @ExceptionHandler(PaymentNotFoundException.class)
  public ProblemDetail handleNotFound(PaymentNotFoundException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(AcquiringBankUnavailableException.class)
  public ProblemDetail handleBankUnavailable(AcquiringBankUnavailableException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
        "The acquiring bank could not process the payment. No payment was made; "
            + "it is safe to try again.");
    problem.setTitle("Acquiring bank unavailable");
    return problem;
  }

  @ExceptionHandler(AcquiringBankTimeoutException.class)
  public ProblemDetail handleBankTimeout(AcquiringBankTimeoutException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
        "The acquiring bank did not respond in time, so the payment outcome is unknown. "
            + "Trying again may charge the shopper twice.");
    problem.setTitle("Acquiring bank timeout");
    return problem;
  }

  private ProblemDetail rejected(String detail) {
    metrics.recordOutcome(PaymentStatus.REJECTED);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setTitle("Payment rejected");
    // "status" is the HTTP status in a problem detail, so the payment outcome needs its own key.
    problem.setProperty("payment_status", PaymentStatus.REJECTED);
    return problem;
  }

  /**
   * Reports fields by their JSON names (expiry_month), not their Java names (expiryMonth).
   */
  private static String toSnakeCase(String field) {
    return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }

  record FieldError(String field, String message) {

  }
}
