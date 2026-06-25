// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.entity.DirectoryConnection;
import io.micrometer.core.instrument.Tags;

/**
 * Single source of truth for the per-directory meter tags shared across the
 * observability binders ({@link LdapPoolMetrics}, {@link LdapOperationMetrics}).
 *
 * <p>Keeping the key/value derivation here means pool and operation series can
 * be joined on identical {@code directory_id} / {@code directory} / {@code type}
 * labels — they can't drift apart. All three are bounded, low-cardinality
 * dimensions; {@code directory_id} is the stable key, {@code directory}
 * (display name) is a human-readable convenience that re-keys on rename.</p>
 */
final class DirectoryMeterTags {

    private DirectoryMeterTags() {}

    static Tags of(DirectoryConnection dc) {
        return Tags.of(
                "directory_id", String.valueOf(dc.getId()),
                "directory", dc.getDisplayName() == null ? "" : dc.getDisplayName(),
                "type", dc.getDirectoryType() == null ? "UNKNOWN" : dc.getDirectoryType().name());
    }
}
