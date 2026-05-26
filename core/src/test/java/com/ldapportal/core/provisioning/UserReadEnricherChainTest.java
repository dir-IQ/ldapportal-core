// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.model.LdapUser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the chain's two routing modes (empty vs registered) and
 * the multi-enricher fold so a future addon that adds an
 * enricher can rely on stable composition semantics.
 */
class UserReadEnricherChainTest {

    private final DirectoryConnection dc = directoryConnection();
    private final LdapUser alice = new LdapUser("uid=alice,dc=x",
            mapOf("uid", "alice", "cn", "Alice"));

    @Test
    void emptyChain_passesUsersThrough_unchanged() {
        UserReadEnricherChain chain = new UserReadEnricherChain(List.of());

        assertThat(chain.enrich(dc, alice)).isSameAs(alice);
        assertThat(chain.enrichBatch(dc, List.of(alice)))
                .hasSize(1)
                .first().isSameAs(alice);
    }

    @Test
    void nullList_treatedAsEmpty() {
        UserReadEnricherChain chain = new UserReadEnricherChain(null);
        assertThat(chain.hasEnrichers()).isFalse();
        assertThat(chain.enrich(dc, alice)).isSameAs(alice);
    }

    @Test
    void emptyBatch_shortCircuits() {
        UserReadEnricherChain chain = new UserReadEnricherChain(
                List.of(taggingEnricher("anything", "x")));

        // Empty batch in → empty batch out, no enricher invocation.
        // We don't have a way to assert "enricher not called" without
        // a spy, but the contract is "empty input always returns empty"
        // — pin the output.
        assertThat(chain.enrichBatch(dc, List.of())).isEmpty();
    }

    @Test
    void singleEnricher_appliesAugmentation() {
        UserReadEnricherChain chain = new UserReadEnricherChain(
                List.of(taggingEnricher("isva.orphaned", "false")));

        LdapUser got = chain.enrich(dc, alice);

        assertThat(got.getFirstValue("isva.orphaned")).isEqualTo("false");
        // Original attributes preserved.
        assertThat(got.getFirstValue("cn")).isEqualTo("Alice");
    }

    @Test
    void multipleEnrichers_composeInOrder() {
        // First adds isva.orphaned=false; second adds another tag.
        // Output should carry BOTH augmentations because the fold
        // passes the previous enricher's output to the next one's
        // input.
        UserReadEnricherChain chain = new UserReadEnricherChain(List.of(
                taggingEnricher("isva.orphaned", "false"),
                taggingEnricher("vendor.audit", "checked")));

        LdapUser got = chain.enrich(dc, alice);

        assertThat(got.getFirstValue("isva.orphaned")).isEqualTo("false");
        assertThat(got.getFirstValue("vendor.audit")).isEqualTo("checked");
    }

    @Test
    void enrichBatch_preservesOrderAndSize() {
        // Order + size invariants are part of the SPI contract; the
        // chain's fold must preserve them across composed enrichers.
        LdapUser bob = new LdapUser("uid=bob,dc=x", mapOf("uid", "bob"));
        LdapUser carol = new LdapUser("uid=carol,dc=x", mapOf("uid", "carol"));
        UserReadEnricherChain chain = new UserReadEnricherChain(
                List.of(taggingEnricher("isva.orphaned", "true")));

        List<LdapUser> got = chain.enrichBatch(dc, List.of(alice, bob, carol));

        assertThat(got).hasSize(3);
        assertThat(got).extracting(LdapUser::getDn)
                .containsExactly("uid=alice,dc=x", "uid=bob,dc=x", "uid=carol,dc=x");
        assertThat(got).allSatisfy(u ->
                assertThat(u.getFirstValue("isva.orphaned")).isEqualTo("true"));
    }

    @Test
    void hasEnrichers_reflectsState() {
        assertThat(new UserReadEnricherChain(List.of()).hasEnrichers()).isFalse();
        assertThat(new UserReadEnricherChain(
                List.of(taggingEnricher("x", "y"))).hasEnrichers()).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────

    /**
     * Returns a minimal enricher that adds a single attribute to
     * every user it sees. Verifies the chain's add-augmentation
     * contract without pulling in the real ISVA implementation.
     */
    private static UserReadEnricher taggingEnricher(String attr, String value) {
        return (dir, users) -> users.stream()
                .map(u -> {
                    Map<String, List<String>> augmented = new HashMap<>(u.getAttributes());
                    augmented.put(attr, List.of(value));
                    return new LdapUser(u.getDn(), augmented);
                })
                .toList();
    }

    private static DirectoryConnection directoryConnection() {
        DirectoryConnection d = new DirectoryConnection();
        d.setDirectoryType(DirectoryType.OPENLDAP);
        return d;
    }

    private static Map<String, List<String>> mapOf(String... kv) {
        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], new ArrayList<>(List.of(kv[i + 1])));
        }
        return m;
    }
}
