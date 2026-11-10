// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** What a content verify found out about the entry a held reference names. */
public enum SyncHeldStatus {
    /** It doesn't exist on the source: fix the reference there; a reconcile can't. */
    DANGLING,
    /** It exists on the source but no sync set places it yet: the value fills in once one does. */
    UNSYNCED,
    /** It wasn't looked up (over the lookup limit, or the read failed). */
    UNCHECKED
}
