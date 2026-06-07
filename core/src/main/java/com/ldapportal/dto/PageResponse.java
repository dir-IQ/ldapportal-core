// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable, top-level JSON envelope for a page of results. Spring's direct
 * {@link Page} serialization is deprecated and its shape is version-dependent
 * (Spring Boot 3.3+ nests the metadata under a {@code page} object), so
 * endpoints return this instead to give the UI a guaranteed contract.
 */
public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int number,
        int size) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getTotalElements(),
                page.getTotalPages(), page.getNumber(), page.getSize());
    }
}
