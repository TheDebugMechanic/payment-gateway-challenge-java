package com.checkout.payment.gateway.exception;

/**
 * One invalid field in a problem detail's {@code errors} list.
 *
 * @param field   the JSON name of the field
 * @param message why the value is invalid
 */
public record FieldError(String field, String message) {

}
