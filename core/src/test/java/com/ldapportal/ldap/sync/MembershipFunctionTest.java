// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.SyncTransformRule;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.sync.identity.EntryUuidIdentityStrategy;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link MembershipFunction}: applicability gating,
 * placement, attribute rename / value-template transforms, the sourceAnchor
 * stamp, DN-reference remapping via a stub resolver, identity exclusion, and
 * content-hash stability.
 */
class MembershipFunctionTest {

    private final MembershipFunction fn = new MembershipFunction();
    private final IdentityStrategy strategy = new EntryUuidIdentityStrategy();

    private SyncSet baseSet() {
        SyncSet s = new SyncSet();
        s.setName("people");
        s.setObjectScopeBaseDn("ou=people,dc=src");
        s.setTargetBaseDn("ou=Users,dc=dst");
        return s;
    }

    private Entry person() {
        return new Entry("uid=alice,ou=people,dc=src",
                new Attribute("objectClass", "top", "inetOrgPerson"),
                new Attribute("entryUUID", "1111-AAAA"),
                new Attribute("uid", "alice"),
                new Attribute("cn", "Alice Adams"),
                new Attribute("employeeType", "staff"));
    }

    private ReferenceResolver none() {
        return dn -> Optional.empty();
    }

    @Test
    void identity_isNormalizedEntryUuid_andOperationalAttrsAreNotCopied() {
        MembershipDecision d = fn.evaluate(baseSet(), strategy, person(), none());
        assertThat(d.member()).isTrue();
        assertThat(d.identity()).isEqualTo("1111-aaaa"); // lower-cased
        assertThat(d.targetDn()).isEqualTo("uid=alice,ou=Users,dc=dst");
        assertThat(d.desiredAttrs()).extracting(Attribute::getName)
                .doesNotContain("entryUUID")
                .contains("uid", "cn", "objectClass", "employeeType");
    }

    @Test
    void applicabilityFilter_excludesNonMatching() {
        SyncSet set = baseSet();
        set.setApplicabilityFilter("(employeeType=staff)");
        assertThat(fn.evaluate(set, strategy, person(), none()).member()).isTrue();

        Entry contractor = person().duplicate();
        contractor.setAttribute("employeeType", "contractor");
        assertThat(fn.evaluate(set, strategy, contractor, none()).member()).isFalse();
    }

    @Test
    void transformRules_renameAndTemplateAttributes() {
        SyncSet set = baseSet();
        set.setTransformRules(List.of(
                new SyncTransformRule("uid", "sAMAccountName", null),
                new SyncTransformRule("cn", "displayName", "Mr ${value}")));
        MembershipDecision d = fn.evaluate(set, strategy, person(), none());

        assertThat(vals(d, "sAMAccountName")).containsExactly("alice");
        assertThat(vals(d, "displayName")).containsExactly("Mr Alice Adams");
        assertThat(vals(d, "uid")).isNull(); // renamed away
    }

    @Test
    void sourceAnchor_isStampedWhenConfigured() {
        SyncSet set = baseSet();
        set.setSourceAnchorAttribute("description");
        MembershipDecision d = fn.evaluate(set, strategy, person(), none());
        assertThat(vals(d, "description")).containsExactly("1111-aaaa");
    }

    @Test
    void referenceAttributes_areRemappedAndUnresolvedDropped() {
        SyncSet set = baseSet();
        set.setObjectScopeBaseDn("ou=groups,dc=src");
        set.setTargetBaseDn("ou=Groups,dc=dst");
        set.setReferenceAttributes("member");
        Entry group = new Entry("cn=eng,ou=groups,dc=src",
                new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("entryUUID", "9999"),
                new Attribute("cn", "eng"),
                new Attribute("member", "uid=alice,ou=people,dc=src", "uid=bob,ou=people,dc=src"));

        ReferenceResolver resolver = dn -> dn.startsWith("uid=alice")
                ? Optional.of("uid=alice,ou=Users,dc=dst") : Optional.empty();

        MembershipDecision d = fn.evaluate(set, strategy, group, resolver);
        // alice remapped, bob (unresolved) dropped.
        assertThat(vals(d, "member")).containsExactly("uid=alice,ou=Users,dc=dst");
    }

    @Test
    void contentHash_isStableForEquivalentEntries() {
        byte[] h1 = fn.evaluate(baseSet(), strategy, person(), none()).contentHash();
        byte[] h2 = fn.evaluate(baseSet(), strategy, person(), none()).contentHash();
        assertThat(h1).isEqualTo(h2);

        Entry changed = person().duplicate();
        changed.setAttribute("cn", "Alice B Adams");
        assertThat(fn.evaluate(baseSet(), strategy, changed, none()).contentHash()).isNotEqualTo(h1);
    }

    @Test
    void entraType_hasNoLdapIdentity_soEntryIsOut() {
        IdentityStrategy entra = new com.ldapportal.ldap.sync.identity.EntraIdentityStrategy();
        // Entra strategy has a null identity attribute → no identity → OUT.
        assertThat(entra.supports(DirectoryType.ENTRA_ID)).isTrue();
        assertThat(fn.evaluate(baseSet(), entra, person(), none()).member()).isFalse();
    }

    private static String[] vals(MembershipDecision d, String name) {
        for (Attribute a : d.desiredAttrs()) {
            if (a.getName().equalsIgnoreCase(name)) {
                return a.getValues();
            }
        }
        return null;
    }
}
