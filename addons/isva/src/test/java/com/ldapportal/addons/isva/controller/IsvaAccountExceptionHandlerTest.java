// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.service.IsvaAccountRefusalException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handler is a single mapper; the test pins the on-wire shape of
 * the ProblemDetail body for each documented refusal code so a future
 * accidental drop of the {@code code} property fails here loudly.
 * Status and code are part of the public API surface — frontends
 * dispatch CTAs on these.
 */
class IsvaAccountExceptionHandlerTest {

    private final IsvaAccountExceptionHandler handler = new IsvaAccountExceptionHandler();

    @Test
    void mapsConflictRefusal_withCode_andMessage() {
        ProblemDetail pd = handler.handleRefusal(new IsvaAccountRefusalException(
                HttpStatus.CONFLICT, "ivia_already_linked",
                "An IVIA account already exists for this identity"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getDetail())
                .isEqualTo("An IVIA account already exists for this identity");
        assertThat(pd.getProperties())
                .containsEntry("code", "ivia_already_linked");
    }

    @Test
    void mapsBadRequestRefusal_renewNotForward_carriesCode() {
        ProblemDetail pd = handler.handleRefusal(new IsvaAccountRefusalException(
                HttpStatus.BAD_REQUEST, "ivia_renew_not_forward",
                "validUntil must extend forward"));

        // The one non-409 in the refusal table — pinning so a future
        // change to a blanket 409 mapping doesn't slip through.
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("code", "ivia_renew_not_forward");
    }

    @Test
    void allDocumentedCodesRoundTrip() {
        // Pinning every public code so adding a new one without a docs/
        // frontend update gets flagged at review.
        for (String code : new String[]{
                "ivia_already_linked",
                "ivia_orphan",
                "ivia_force_off",
                "ivia_directory_disabled",
                "ivia_renew_not_forward",
                "ivia_state_changed"}) {
            HttpStatus status = code.equals("ivia_renew_not_forward")
                    ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
            ProblemDetail pd = handler.handleRefusal(
                    new IsvaAccountRefusalException(status, code, "msg"));
            assertThat(pd.getProperties()).containsEntry("code", code);
            assertThat(pd.getStatus()).isEqualTo(status.value());
        }
    }
}
