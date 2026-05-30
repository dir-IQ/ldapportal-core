// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DeleteRequest;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ModifyDNRequest;
import com.unboundid.ldap.sdk.ModifyRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationPayloadCodecTest {

    @Test
    void decodeAdd_buildsAddRequestWithAttributes() {
        Map<String, Object> payload = Map.of(
                "attributes", Map.of(
                        "objectClass", List.of("top", "person"),
                        "cn",          List.of("Alice"),
                        "sn",          List.of("Smith")));

        AddRequest req = ReplicationPayloadCodec.decodeAdd("uid=alice,dc=corp", payload);

        assertThat(req.getDN()).isEqualTo("uid=alice,dc=corp");
        // Order isn't part of the contract; compare as a set.
        assertThat(req.getAttributes())
                .extracting(Attribute::getName)
                .containsExactlyInAnyOrder("objectClass", "cn", "sn");
        Attribute cn = req.getAttributes().stream()
                .filter(a -> a.getName().equals("cn")).findFirst().orElseThrow();
        assertThat(cn.getValues()).containsExactly("Alice");
    }

    @Test
    void decodeModify_buildsModifyRequestWithCorrectTypes() {
        Map<String, Object> payload = Map.of(
                "modifications", List.of(
                        Map.of("type", "REPLACE", "name", "mail",
                               "values", List.of("new@corp.com")),
                        Map.of("type", "ADD",     "name", "telephoneNumber",
                               "values", List.of("+1 555 1234")),
                        Map.of("type", "DELETE",  "name", "description",
                               "values", List.of())));

        ModifyRequest req = ReplicationPayloadCodec.decodeModify("uid=alice,dc=corp", payload);

        assertThat(req.getDN()).isEqualTo("uid=alice,dc=corp");
        List<Modification> mods = req.getModifications();
        assertThat(mods).hasSize(3);
        assertThat(mods.get(0).getModificationType()).isEqualTo(ModificationType.REPLACE);
        assertThat(mods.get(0).getAttributeName()).isEqualTo("mail");
        assertThat(mods.get(1).getModificationType()).isEqualTo(ModificationType.ADD);
        assertThat(mods.get(2).getModificationType()).isEqualTo(ModificationType.DELETE);
    }

    @Test
    void decodeDelete_returnsRequestWithDnOnly() {
        DeleteRequest req = ReplicationPayloadCodec.decodeDelete("uid=alice,dc=corp");
        assertThat(req.getDN()).isEqualTo("uid=alice,dc=corp");
    }

    @Test
    void decodeModifyDn_withNewSuperior() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("newRdn",        "uid=alice2");
        payload.put("deleteOldRdn",  true);
        payload.put("newSuperiorDn", "ou=archive,dc=corp");

        ModifyDNRequest req = ReplicationPayloadCodec.decodeModifyDn("uid=alice,dc=corp", payload);

        assertThat(req.getDN()).isEqualTo("uid=alice,dc=corp");
        assertThat(req.getNewRDN()).isEqualTo("uid=alice2");
        assertThat(req.deleteOldRDN()).isTrue();
        assertThat(req.getNewSuperiorDN()).isEqualTo("ou=archive,dc=corp");
    }

    @Test
    void decodeModifyDn_withoutNewSuperior_treatsAsRename() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("newRdn",        "uid=bob");
        payload.put("deleteOldRdn",  false);
        // newSuperiorDn omitted

        ModifyDNRequest req = ReplicationPayloadCodec.decodeModifyDn("uid=alice,dc=corp", payload);

        assertThat(req.getNewRDN()).isEqualTo("uid=bob");
        assertThat(req.deleteOldRDN()).isFalse();
        assertThat(req.getNewSuperiorDN()).isNull();
    }

    @Test
    void decodeModify_unknownTypeDefaultsToReplace() {
        // Defensive: an unknown type string (perhaps from a JSON corruption
        // or a future enum value not yet supported) decodes to REPLACE
        // rather than throwing. REPLACE is the safest fallback — DELETE
        // would silently destroy data and ADD would conflict.
        Map<String, Object> payload = Map.of(
                "modifications", List.of(
                        Map.of("type", "BOGUS", "name", "cn",
                               "values", List.of("New Name"))));

        ModifyRequest req = ReplicationPayloadCodec.decodeModify("uid=alice,dc=corp", payload);
        assertThat(req.getModifications().get(0).getModificationType())
                .isEqualTo(ModificationType.REPLACE);
    }
}
