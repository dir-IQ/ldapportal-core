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
}
