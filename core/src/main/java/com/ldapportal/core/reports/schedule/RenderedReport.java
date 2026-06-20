// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

/**
 * The product of a {@link ReportRenderer}: the rendered bytes together with the
 * metadata needed to deliver them (as an email attachment or an S3 object).
 *
 * @param bytes       the rendered document
 * @param contentType MIME type, e.g. {@code text/csv} or {@code application/pdf}
 * @param filename    suggested filename, e.g. {@code disabled-accounts.csv}
 */
public record RenderedReport(byte[] bytes, String contentType, String filename) {

    public RenderedReport {
        if (bytes == null) {
            throw new IllegalArgumentException("RenderedReport bytes must not be null");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("RenderedReport contentType must not be blank");
        }
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("RenderedReport filename must not be blank");
        }
    }
}
