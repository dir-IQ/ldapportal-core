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
 * @param configBindDn   config-admin bind DN (e.g. {@code cn=admin,cn=config}); OpenLDAP only
 * @param configPassword config-admin password; OpenLDAP only
 */
public record ApplySchemaPreviewRequest(
        String configBindDn,
        String configPassword) {
}
