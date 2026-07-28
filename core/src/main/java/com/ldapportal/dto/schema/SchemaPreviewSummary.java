// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.schema;

import com.ldapportal.entity.enums.DirectoryType;

import java.util.List;
import java.util.UUID;

/**
 * The result of previewing a schema-LDIF upload: every element classified,
 * with rollup counts and an overall {@code blocking} flag the apply endpoint
 * refuses to override.
 *
 * @param previewId  short-lived id the apply call references
 * @param directoryId target directory
 * @param vendor     resolved directory type (drives the write mechanics)
 * @param total      number of schema elements found
 * @param counts     rollup by action
 * @param elements   every classified element
 * @param blocking   true when any element is blocking — apply is refused
 */
public record SchemaPreviewSummary(
        String previewId,
        UUID directoryId,
        DirectoryType vendor,
        int total,
        Counts counts,
        List<SchemaPreviewElement> elements,
        boolean blocking) {

    public record Counts(int addNew, int modifyExisting, int unsupported, int errors) {}
}
