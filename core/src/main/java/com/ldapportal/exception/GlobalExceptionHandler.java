// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.exception;

import com.ldapportal.core.entitlement.EntitlementMissingException;
import com.ldapportal.core.entitlement.LimitExceededException;
import com.ldapportal.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates application exceptions into RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Status mapping:
 * <ul>
 *   <li>400 — validation failures ({@link MethodArgumentNotValidException})</li>
 *   <li>400 — illegal argument ({@link IllegalArgumentException})</li>
 *   <li>401 — bad credentials ({@link BadCredentialsException})</li>
 *   <li>402 — feature not licensed ({@link EntitlementMissingException})</li>
 *   <li>403 — access denied ({@link AccessDeniedException})</li>
 *   <li>404 — resource not found ({@link ResourceNotFoundException})</li>
 *   <li>409 — duplicate/conflict ({@link ConflictException})</li>
 *   <li>500 — everything else</li>
 * </ul>
 * </p>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (a, b) -> a));

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /**
     * Feature gated by the current license. Carries the required entitlement
     * and current edition in the ProblemDetail so the frontend can render
     * an upgrade prompt with accurate target-tier information.
     *
     * <p>The {@code code} property distinguishes this kind of 402 from
     * {@link LimitExceededException}'s 402 — both map to Payment Required
     * but the frontend routes them to different upgrade prompts.</p>
     */
    @ExceptionHandler(EntitlementMissingException.class)
    public ProblemDetail handleEntitlementMissing(EntitlementMissingException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
        pd.setProperty("code", "ENTITLEMENT_MISSING");
        pd.setProperty("entitlement", ex.getEntitlement().name());
        pd.setProperty("currentEdition", ex.getCurrentEdition().name());
        return pd;
    }

    /**
     * License-declared resource cap reached. 402 with the limit type,
     * current count, maximum, and edition so the frontend can render
     * "you're at N/M X on the &lt;edition&gt; tier" with an upgrade CTA.
     */
    @ExceptionHandler(LimitExceededException.class)
    public ProblemDetail handleLimitExceeded(LimitExceededException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
        pd.setProperty("code", "LIMIT_EXCEEDED");
        pd.setProperty("limitType", ex.getLimitType().name());
        pd.setProperty("currentCount", ex.getCurrentCount());
        pd.setProperty("maximum", ex.getMaximum());
        pd.setProperty("currentEdition", ex.getCurrentEdition().name());
        return pd;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource was modified by another request; please reload and retry");
    }

    @ExceptionHandler(LdapConnectionException.class)
    public ProblemDetail handleLdapConnection(LdapConnectionException ex) {
        log.warn("LDAP connection error: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "LDAP server unreachable: " + ex.getMessage());
    }

    @ExceptionHandler(LdapOperationException.class)
    public ProblemDetail handleLdapOperation(LdapOperationException ex) {
        log.warn("LDAP operation error: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail handleTooManyRequests(TooManyRequestsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
