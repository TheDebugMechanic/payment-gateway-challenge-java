package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.service.PaymentMetrics;
import java.util.Comparator;
import java.util.List;
import tools.jackson.core.exc.StreamReadException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Turns failures into RFC 9457 problem details.
 */
@RestControllerAdvice
public class CommonExceptionHandler {

  private final PaymentMetrics metrics;

  /**
   * Creates the handler.
   *
   * @param metrics records rejected requests
   */
  public CommonExceptionHandler(PaymentMetrics metrics) {
    this.metrics = metrics;
  }

  /**
   * One or more fields have the wrong type or failed validation.
   *
   * @param ex the failure, which carries every invalid field
   * @return a {@code 400} problem listing every invalid field
   */
  @ExceptionHandler(InvalidPaymentRequestException.class)
  public ProblemDetail handleInvalidRequest(InvalidPaymentRequestException ex) {
    List<FieldError> errors = ex.errors().stream()
        .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
        .toList();
    ProblemDetail problem = rejected("One or more fields are invalid");
    problem.setProperty("errors", errors);
    return problem;
  }

  /**
   * The body is missing or isn't valid JSON, or a path variable has the wrong type.
   *
   * @param ex       the failure to read the input
   * @param exchange the current request, used to tell a payment POST from other requests
   * @return a {@code 400} problem
   */
  @ExceptionHandler(ServerWebInputException.class)
  public ProblemDetail handleUnreadableRequest(ServerWebInputException ex,
      ServerWebExchange exchange) {
    if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
      return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getReason());
    }
    for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof StreamReadException) {
        return rejected("The request body is not valid JSON");
      }
    }
    return rejected("The request body is missing");
  }

  /**
   * No payment has the requested id.
   *
   * @param ex the not-found failure
   * @return a {@code 404} problem
   */
  @ExceptionHandler(PaymentNotFoundException.class)
  public ProblemDetail handleNotFound(PaymentNotFoundException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  /**
   * The bank failed, so no payment was made and it is safe to try again.
   *
   * @param ex the bank failure
   * @return a {@code 502} problem
   */
  @ExceptionHandler(AcquiringBankUnavailableException.class)
  public ProblemDetail handleBankUnavailable(AcquiringBankUnavailableException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
        "The acquiring bank could not process the payment. No payment was made; "
            + "it is safe to try again.");
    problem.setTitle("Acquiring bank unavailable");
    return problem;
  }

  /**
   * The bank did not answer in time, so the payment outcome is unknown.
   *
   * @param ex the timeout
   * @return a {@code 504} problem
   */
  @ExceptionHandler(AcquiringBankTimeoutException.class)
  public ProblemDetail handleBankTimeout(AcquiringBankTimeoutException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
        "The acquiring bank did not respond in time, so the payment outcome is unknown. "
            + "Trying again may charge the shopper twice.");
    problem.setTitle("Acquiring bank timeout");
    return problem;
  }

  /**
   * Builds the {@code 400} problem for a rejected payment and counts it in the metrics.
   *
   * @param detail the human-readable explanation
   * @return the problem detail
   */
  private ProblemDetail rejected(String detail) {
    metrics.recordOutcome(PaymentStatus.REJECTED);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setTitle("Payment rejected");
    // "status" is the HTTP status in a problem detail, so the payment outcome needs its own key.
    problem.setProperty("payment_status", PaymentStatus.REJECTED);
    return problem;
  }
}
