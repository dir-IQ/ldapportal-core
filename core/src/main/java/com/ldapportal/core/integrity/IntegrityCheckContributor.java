// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.integrity;

import com.ldapportal.dto.ldap.IntegrityReport.IntegrityIssue;
import com.ldapportal.entity.DirectoryConnection;

import java.util.List;

/**
 * SPI for addon-contributed integrity checks of DN-valued attributes other
 * than group membership. Core checks {@code member} / {@code uniqueMember}
 * itself; a vendor-specific reference (e.g. an IVIA account's {@code secDN})
 * is checked by its addon, which plugs in here, so core never imports addon
 * code and community builds, with no contributor on the classpath, offer only
 * the built-in checks.
 *
 * <p>{@link com.ldapportal.ldap.IntegrityCheckService} injects
 * {@code List<IntegrityCheckContributor>} (empty without addons) and runs each
 * contributor that applies to the directory for the
 * {@link com.ldapportal.dto.ldap.IntegrityReport.IssueType#BROKEN_REFERENCE}
 * check. Mirrors {@link com.ldapportal.core.reports.OperationalReportProvider}.</p>
 */
public interface IntegrityCheckContributor {

    /**
     * The attributes this contributor checks on {@code dir} (for the check's
     * label, e.g. {@code secDN}), or empty when it doesn't apply to the
     * directory (e.g. it has no active vendor configuration).
     */
    List<String> referenceAttributes(DirectoryConnection dir);

    /**
     * The directory's broken references: {@code BROKEN_REFERENCE} issues whose
     * {@code dn} is the entry holding the reference, {@code attribute} the
     * attribute, and {@code value} the DN that names no entry (or {@code null}
     * when a required reference is missing altogether). Called only when
     * {@link #referenceAttributes} is non-empty.
     */
    List<IntegrityIssue> brokenReferences(DirectoryConnection dir);
}
