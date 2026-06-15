// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.discovery;

/**
 * Request body for the directory discovery scan.
 */
public record DiscoveryRequest(
        /** Root DN to start scanning from. Null → use directory baseDn. */
        String rootDn,
        /** Number of entries to sample per OU (default 20, max 50). */
        Integer sampleSize,
        /** Whether to include group analysis (default true). */
        Boolean includeGroups
) {
    public int effectiveSampleSize() {
        if (sampleSize == null || sampleSize <= 0) return 20;
        return Math.min(sampleSize, 50);
    }

    public boolean effectiveIncludeGroups() {
        return includeGroups == null || includeGroups;
    }
}
