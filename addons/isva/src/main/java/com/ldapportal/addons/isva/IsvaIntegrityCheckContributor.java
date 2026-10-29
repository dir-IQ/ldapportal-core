// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.core.integrity.IntegrityCheckContributor;
import com.ldapportal.dto.ldap.IntegrityReport.IntegrityIssue;
import com.ldapportal.dto.ldap.IntegrityReport.IssueType;
import com.ldapportal.entity.DirectoryConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Integrity checker's {@code secDN} check: each secUser in the management DIT
 * whose {@code secDN} names no entry, or that has no {@code secDN}, is a
 * {@code BROKEN_REFERENCE}. The same scan as the Orphaned IVIA accounts report
 * ({@link IsvaOrphanedSecUsers}), so it applies to the same directories: those
 * with an active linked-mode IVIA configuration.
 */
@Component
@RequiredArgsConstructor
public class IsvaIntegrityCheckContributor implements IntegrityCheckContributor {

    static final String SEC_DN = "secDN";

    private final IsvaOrphanedSecUsers orphanedSecUsers;

    @Override
    public List<String> referenceAttributes(DirectoryConnection dir) {
        return orphanedSecUsers.activeLinkedConfigOrNull(dir) != null ? List.of(SEC_DN) : List.of();
    }

    @Override
    public List<IntegrityIssue> brokenReferences(DirectoryConnection dir) {
        VendorIntegrationIsvaConfig cfg = orphanedSecUsers.activeLinkedConfigOrNull(dir);
        if (cfg == null) {
            return List.of();
        }
        List<IntegrityIssue> issues = new ArrayList<>();
        for (IsvaOrphanedSecUsers.Orphan o : orphanedSecUsers.find(dir, cfg)) {
            String description = o.secDn() == null || o.secDn().isBlank()
                    ? "Attribute 'secDN' is missing: the account names no user entry"
                    : "Attribute 'secDN' references non-existent DN: " + o.secDn();
            issues.add(new IntegrityIssue(IssueType.BROKEN_REFERENCE, o.secUserDn(), description, SEC_DN,
                    o.secDn() == null || o.secDn().isBlank() ? null : o.secDn()));
        }
        return issues;
    }
}
