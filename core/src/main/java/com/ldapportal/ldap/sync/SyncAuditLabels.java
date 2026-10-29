// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.SyncLinkRepository;

import java.util.Map;
import java.util.UUID;

/**
 * The human-readable names a directory-sync audit record carries in its
 * detail: the sync set's name and the display names of the link's source and
 * target directories, as they were when the record was written. A name that
 * can't be resolved is left out.
 */
public record SyncAuditLabels(String syncSetName, String sourceDirectory, String targetDirectory) {

    public static final SyncAuditLabels NONE = new SyncAuditLabels(null, null, null);

    /** Resolve the names for {@code set}; never throws. */
    public static SyncAuditLabels of(SyncSet set, SyncLinkRepository linkRepo,
                                     DirectoryConnectionRepository directoryRepo) {
        if (set == null) {
            return NONE;
        }
        try {
            SyncLink link = set.getLinkId() == null ? null : linkRepo.findById(set.getLinkId()).orElse(null);
            return of(set.getName(), link, directoryRepo);
        } catch (Exception ex) {
            return new SyncAuditLabels(set.getName(), null, null);
        }
    }

    /** Resolve the directory names of {@code link}; never throws. */
    public static SyncAuditLabels of(String syncSetName, SyncLink link, DirectoryConnectionRepository directoryRepo) {
        if (link == null) {
            return new SyncAuditLabels(syncSetName, null, null);
        }
        return new SyncAuditLabels(syncSetName,
                directoryName(link.getSourceDirId(), directoryRepo),
                directoryName(link.getTargetDirId(), directoryRepo));
    }

    private static String directoryName(UUID id, DirectoryConnectionRepository directoryRepo) {
        if (id == null) {
            return null;
        }
        try {
            return directoryRepo.findById(id).map(DirectoryConnection::getDisplayName).orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Add the names that resolved to {@code detail}. */
    public void putInto(Map<String, Object> detail) {
        if (syncSetName != null) {
            detail.put("syncSetName", syncSetName);
        }
        if (sourceDirectory != null) {
            detail.put("sourceDirectory", sourceDirectory);
        }
        if (targetDirectory != null) {
            detail.put("targetDirectory", targetDirectory);
        }
    }
}
