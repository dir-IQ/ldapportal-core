// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** Where a sync set's content verification has got to. */
public enum SyncVerifyPhase {
    /** Reading and projecting the source scope. */
    SOURCE,
    /** Reading the entries under the target base. */
    TARGET,
    /** Comparing each expected entry with the target's. */
    COMPARING,
    /** Finished (or failed). */
    DONE
}
