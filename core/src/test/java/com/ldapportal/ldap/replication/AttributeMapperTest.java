// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.ReplicationLinkAttrMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeMapperTest {

    @Test
    void identityMapping_withNoRules_returnsAttributesUnchanged() {
        ReplicationLink link = new ReplicationLink();
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
        ReplicationLink link = link(rule("uid", "sAMAccountName", null));
        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(
                Map.of("uid", List.of("alice")), link);
        assertThat(mapped).containsOnlyKeys("sAMAccountName");
        assertThat(mapped.get("sAMAccountName")).containsExactly("alice");
    }

    @Test
    void valueTemplate_appliesSubstitution() {
        ReplicationLink link = link(rule("mail", "mail", "${value}@corp.com"));
        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(
                Map.of("mail", List.of("alice", "bob")), link);
        assertThat(mapped.get("mail")).containsExactly("alice@corp.com", "bob@corp.com");
    }

    @Test
    void caseInsensitiveAttributeMatching() {
        // LDAP attribute types are case-insensitive. A rule on 'mail'
        // applies to source attrs named 'Mail' / 'MAIL' / etc.
        ReplicationLink link = link(rule("mail", "email", null));
        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(
                Map.of("Mail", List.of("alice@corp.com")), link);
        assertThat(mapped).containsOnlyKeys("email");
    }

    @Test
    void mappingForUnconfiguredAttribute_returnsIdentity() {
        // The lookup helper used by Modification mapping. Attributes
        // with no rule pass through 1:1.
        ReplicationLink link = link(rule("uid", "sAMAccountName", null));
        AttributeMapper.Mapping mapping = AttributeMapper.mappingFor("cn", link);
        assertThat(mapping.targetAttr()).isEqualTo("cn");
        assertThat(mapping.valueTransform().apply("Alice Smith")).isEqualTo("Alice Smith");
    }

    @Test
    void explicitIdentityTemplate_treatedAsIdentity() {
        // Operator types '${value}' verbatim — equivalent to leaving
        // the template field NULL. Pinning the contract so a future
        // optimisation can short-circuit identity templates.
        ReplicationLink link = link(rule("mail", "mail", "${value}"));
        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(
                Map.of("mail", List.of("alice@corp.com")), link);
        assertThat(mapped.get("mail")).containsExactly("alice@corp.com");
    }

    private static ReplicationLink link(ReplicationLinkAttrMapping... rules) {
        ReplicationLink l = new ReplicationLink();
        for (ReplicationLinkAttrMapping r : rules) {
            r.setLink(l);
            l.getAttributeMappings().add(r);
        }
        return l;
    }

    private static ReplicationLinkAttrMapping rule(String sourceAttr, String targetAttr, String template) {
        ReplicationLinkAttrMapping r = new ReplicationLinkAttrMapping();
        r.setSourceAttr(sourceAttr);
        r.setTargetAttr(targetAttr);
        r.setValueTemplate(template);
        return r;
    }
}
