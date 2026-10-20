// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAttributeChange;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPreviewValuesTest {

    @Test
    void replace_showsExactlyTheValuesLostAndGained() {
        Entry before = new Entry("uid=a,dc=x", new Attribute("mail", "old@x"), new Attribute("cn", "a"));

        List<SyncPreviewAttributeChange> c = SyncPreviewValues.forModify(before,
                List.of(new Modification(ModificationType.REPLACE, "mail", "new@x")));

        assertThat(c).singleElement().satisfies(ch -> {
            assertThat(ch.attribute()).isEqualTo("mail");
            assertThat(ch.removed()).containsExactly("old@x");
            assertThat(ch.added()).containsExactly("new@x");
        });
    }

    @Test
    void multiValuedReplace_listsOnlyTheDifference() {
        Entry before = new Entry("cn=g,dc=x", new Attribute("member", "uid=a,dc=x", "uid=b,dc=x"));

        List<SyncPreviewAttributeChange> c = SyncPreviewValues.forModify(before,
                List.of(new Modification(ModificationType.REPLACE, "member", "uid=b,dc=x", "uid=c,dc=x")));

        assertThat(c).singleElement().satisfies(ch -> {
            assertThat(ch.removed()).containsExactly("uid=a,dc=x");
            assertThat(ch.added()).containsExactly("uid=c,dc=x");
        });
    }

    @Test
    void attributeRemoval_listsTheValuesLost() {
        Entry before = new Entry("uid=a,dc=x", new Attribute("description", "gone"));

        List<SyncPreviewAttributeChange> c = SyncPreviewValues.forModify(before,
                List.of(new Modification(ModificationType.DELETE, "description")));

        assertThat(c).singleElement().satisfies(ch -> {
            assertThat(ch.removed()).containsExactly("gone");
            assertThat(ch.added()).isEmpty();
        });
    }

    @Test
    void passwords_areRedacted() {
        List<SyncPreviewAttributeChange> c = SyncPreviewValues.forAdd(
                List.of(new Attribute("userPassword", "{SSHA}abc"), new Attribute("cn", "a")));

        assertThat(c).filteredOn(ch -> ch.attribute().equals("userPassword"))
                .singleElement().satisfies(ch -> assertThat(ch.added()).containsExactly(SyncPreviewValues.REDACTED));
        assertThat(c).filteredOn(ch -> ch.attribute().equals("cn"))
                .singleElement().satisfies(ch -> assertThat(ch.added()).containsExactly("a"));
    }

    @Test
    void passwordHashesAndKeys_areRedacted_notJustUserPassword() {
        for (String secret : List.of("sambaNTPassword", "sambaLMPassword", "unicodePwd", "pwdHistory",
                "ds-pwp-last-login-time-pwd", "userPKCS12", "krbPrincipalKey", "olcRootPW",
                "ibm-slapdAdminPw", "userPassword;binary", "supplementalCredentials", "clientSecret")) {
            assertThat(SyncPreviewValues.isSecret(secret)).as(secret).isTrue();
        }
        for (String plain : List.of("cn", "mail", "member", "pwmEventLog", "description")) {
            assertThat(SyncPreviewValues.isSecret(plain)).as(plain).isFalse();
        }
        List<SyncPreviewAttributeChange> c = SyncPreviewValues.forModify(
                new Entry("uid=a,dc=x", new Attribute("sambaNTPassword", "0123ABCD")),
                List.of(new Modification(ModificationType.REPLACE, "sambaNTPassword", "4567EF01")));
        assertThat(c).singleElement().satisfies(ch -> {
            assertThat(ch.removed()).containsExactly(SyncPreviewValues.REDACTED);
            assertThat(ch.added()).containsExactly(SyncPreviewValues.REDACTED);
        });
    }

    @Test
    void binaryValues_areSummarised() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x10};

        List<SyncPreviewAttributeChange> c = SyncPreviewValues.forAdd(List.of(new Attribute("jpegPhoto", jpeg)));

        assertThat(c.get(0).added()).containsExactly("(binary, 5 bytes)");
    }

    @Test
    void longValueLists_areCapped_andTheRestCounted() {
        List<String> members = new ArrayList<>();
        for (int i = 0; i < SyncPreviewValues.MAX_VALUES + 7; i++) {
            members.add("uid=u" + i + ",dc=x");
        }

        List<SyncPreviewAttributeChange> c = SyncPreviewValues.forAdd(
                List.of(new Attribute("member", members)));

        assertThat(c.get(0).added()).hasSize(SyncPreviewValues.MAX_VALUES);
        assertThat(c.get(0).omittedAdded()).isEqualTo(7);
    }

    @Test
    void hierarchyKey_ordersParentsBeforeTheirSubtree() {
        List<String> dns = new ArrayList<>(List.of(
                "uid=z,ou=b,dc=x", "ou=b,dc=x", "uid=a,ou=c,dc=x", "ou=c,dc=x", "dc=x"));

        dns.sort((l, r) -> SyncPreviewService.hierarchyKey(l).compareTo(SyncPreviewService.hierarchyKey(r)));

        assertThat(dns).containsExactly("dc=x", "ou=b,dc=x", "uid=z,ou=b,dc=x", "ou=c,dc=x", "uid=a,ou=c,dc=x");
    }
}
