// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The preview's note for reference attributes held back because their referents aren't synced. */
class RecomputeEngineHeldNoteTest {

    @Test
    void oneReference_kept() {
        assertThat(RecomputeEngine.heldNote(Map.of("secDN", List.of("cn=eng,o=native")), false))
                .isEqualTo("secDN kept as it is on the target: it points to cn=eng,o=native, which isn't synced "
                        + "yet. The sync updates it once that entry is synced.");
    }

    @Test
    void manyReferences_leftOutOfAnAdd_listTheFirstFew() {
        Map<String, List<String>> held = new LinkedHashMap<>();
        held.put("member", List.of("uid=a", "uid=b", "uid=c", "uid=d", "uid=e"));

        assertThat(RecomputeEngine.heldNote(held, true))
                .isEqualTo("member left out: it points to 5 entries that aren't synced yet (uid=a; uid=b; uid=c; …). "
                        + "The sync adds it once they're synced.");
    }

    @Test
    void anUnchangedEntry_namesTheValuesTheSyncDoesntWrite() {
        assertThat(RecomputeEngine.notWrittenNote("member", List.of("uid=carol,ou=elsewhere")))
                .isEqualTo("member: 1 value not written, pointing to uid=carol,ou=elsewhere, which isn't synced "
                        + "yet. The sync writes it once that entry is synced.");
    }
}
