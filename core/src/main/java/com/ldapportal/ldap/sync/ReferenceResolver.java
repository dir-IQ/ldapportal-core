// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import java.util.Optional;

/**
 * Resolves a source-side DN-valued reference (a {@code member} / {@code manager}
 * / {@code secDN} value) to the target DN of the referenced identity, using the
 * membership index as the translation table. An unsynced referent resolves to
 * empty and is dropped from the projection (a closure trigger re-emits the
 * referrer when the referent later lands).
 */
@FunctionalInterface
public interface ReferenceResolver {

    Optional<String> resolveTargetDn(String sourceDn);

    /**
     * Whether the entry at {@code sourceDn} is on its way out of its set: a
     * preview's plan takes it out, so its target entry is about to be deleted
     * though the index still records it. Only a preview knows this.
     */
    default boolean leavesItsSet(String sourceDn) {
        return false;
    }

    /**
     * A resolver that answers for {@code sourceDns} without a lookup each:
     * {@link MembershipFunction} hands it every reference value of the entry
     * it's projecting (a large group's members) before resolving them one by
     * one. Other DNs still resolve as here. By default, this resolver itself.
     */
    default ReferenceResolver prefetched(java.util.Collection<String> sourceDns) {
        return this;
    }
}
