// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.schema;

/**
 * Body for the schema-preview apply call.
 *
 * <p>For OpenLDAP the schema lives under {@code cn=config}, which is written by
 * a separate config-admin bind rather than the directory's data bind. Those
 * credentials are supplied here per-request and are <em>never persisted</em>.
 * OpenDJ writes schema with the directory's normal bind, so both fields may be
 * omitted.</p>
 *
 * <p>When {@code addNewOnly} is set, the apply writes only the elements the
 * preview classified as {@code ADD_NEW}, dropping any modification to an
 * existing schema element. Filtering is per-definition, so a single
 * {@code cn=schema} record that bundles both new and existing values applies
 * only its new ones. Defaults to {@code false} (apply everything applicable).</p>
 *
 * @param configBindDn   config-admin bind DN (e.g. {@code cn=admin,cn=config}); OpenLDAP only
 * @param configPassword config-admin password; OpenLDAP only
 * @param addNewOnly     apply only ADD_NEW elements, skipping updates to existing ones
 */
public record ApplySchemaPreviewRequest(
        String configBindDn,
        String configPassword,
        boolean addNewOnly) {
}
