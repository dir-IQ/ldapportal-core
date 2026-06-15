// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.service.IsvaAccountRefusalException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps IVIA verb refusals to RFC 7807 ProblemDetail with a
 * {@code code} property so frontends can dispatch contextual CTAs on
 * a stable symbol instead of parsing the message.
 *
 * <p>{@link Order @Order(HIGHEST_PRECEDENCE)} so this advice wins
 * against the core {@code GlobalExceptionHandler}'s generic
 * {@code RuntimeException} fallback. Without the explicit order,
 * Spring's default tie-break would still land here in practice (most
 * specific exception type wins) but pinning precedence keeps the
 * intent explicit.</p>
 *
 * <p>The addon-local advice is loaded only when the IVIA jar is on
 * the classpath, so a community deployment without the addon never
 * sees these mappings — and never throws the matching exception
 * either, since the service that throws it lives in the same jar.</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IsvaAccountExceptionHandler {

    @ExceptionHandler(IsvaAccountRefusalException.class)
    public ProblemDetail handleRefusal(IsvaAccountRefusalException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setProperty("code", ex.getCode());
        return pd;
    }
}
