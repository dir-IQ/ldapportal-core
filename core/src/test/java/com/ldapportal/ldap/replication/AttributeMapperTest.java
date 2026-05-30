// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.ldap.replication.ReplicationLinkSnapshot.AttrMappingSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeMapperTest {

    @Test
    void identityMapping_withNoRules_returnsAttributesUnchanged() {
        ReplicationLinkSnapshot link = link();
        Map<String, List<String>> source = Map.of(
                "uid",  List.of("alice"),
                "mail", List.of("alice@corp.com"));

        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(source, link);

        assertThat(mapped).containsEntry("uid", List.of("alice"));
        assertThat(mapped).containsEntry("mail", List.of("alice@corp.com"));
    }

    @Test
    void renameMapping_changesAttributeNameOnly() {
        // Common cross-vendor case: source uses uid, target expects
        // sAMAccountName. Value passes through unchanged.
        ReplicationLinkSnapshot link = link(rule("uid", "sAMAccountName", null));
        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(
                Map.of("uid", List.of("alice")), link);
        assertThat(mapped).containsOnlyKeys("sAMAccountName");
        assertThat(mapped.get("sAMAccountName")).containsExactly("alice");
    }

    @Test
    void valueTemplate_appliesSubstitution() {
        ReplicationLinkSnapshot link = link(rule("mail", "mail", "${value}@corp.com"));
        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(
                Map.of("mail", List.of("alice", "bob")), link);
        assertThat(mapped.get("mail")).containsExactly("alice@corp.com", "bob@corp.com");
    }

    @Test
    void caseInsensitiveAttributeMatching() {
        // LDAP attribute types are case-insensitive. A rule on 'mail'
        // applies to source attrs named 'Mail' / 'MAIL' / etc.
        ReplicationLinkSnapshot link = link(rule("mail", "email", null));
        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(
                Map.of("Mail", List.of("alice@corp.com")), link);
        assertThat(mapped).containsOnlyKeys("email");
    }

    @Test
    void mappingForUnconfiguredAttribute_returnsIdentity() {
        // The lookup helper used by Modification mapping. Attributes
        // with no rule pass through 1:1.
        ReplicationLinkSnapshot link = link(rule("uid", "sAMAccountName", null));
        AttributeMapper.Mapping mapping = AttributeMapper.mappingFor("cn", link);
        assertThat(mapping.targetAttr()).isEqualTo("cn");
        assertThat(mapping.valueTransform().apply("Alice Smith")).isEqualTo("Alice Smith");
    }

    @Test
    void explicitIdentityTemplate_treatedAsIdentity() {
        // Operator types '${value}' verbatim — equivalent to leaving
        // the template field NULL. Pinning the contract so a future
        // optimisation can short-circuit identity templates.
        ReplicationLinkSnapshot link = link(rule("mail", "mail", "${value}"));
        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(
                Map.of("mail", List.of("alice@corp.com")), link);
        assertThat(mapped.get("mail")).containsExactly("alice@corp.com");
    }

    private static ReplicationLinkSnapshot link(AttrMappingSnapshot... rules) {
        return new ReplicationLinkSnapshot(
                UUID.randomUUID(), "test-link",
                null, null, null, null, true, false,
                List.of(rules));
    }

    private static AttrMappingSnapshot rule(String sourceAttr, String targetAttr, String template) {
        return new AttrMappingSnapshot(sourceAttr, targetAttr, template);
    }
}
