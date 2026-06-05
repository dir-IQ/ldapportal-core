// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void dataIntegrityViolation_mapsToConflict() {
        ProblemDetail pd = handler.handleDataIntegrity(
                new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_directory_connections_slug\""));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        // The SQL detail is logged, not echoed back to the caller.
        assertThat(pd.getDetail()).doesNotContain("uq_directory_connections_slug");
    }

    @Test
    void preconditionFailed_mapsTo412WithMessage() {
        ProblemDetail pd = handler.handlePreconditionFailed(
                new PreconditionFailedException("resource is at version 5"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.PRECONDITION_FAILED.value());
        assertThat(pd.getDetail()).isEqualTo("resource is at version 5");
    }
}
