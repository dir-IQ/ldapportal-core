// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.ldap.replication.ReplicationLinkSnapshot.AttrMappingSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shared raw → mapped payload shape (§6.3) that both the live
 * enqueuer and the changelog poller route through, so the two capture paths
 * stay byte-identical.
 */
class ReplicationPayloadMapperTest {

    private final UUID correlationId = UUID.randomUUID();

    @Test
    @SuppressWarnings("unchecked")
    void add_appliesAttributeRenameAndValueTemplate() {
        ReplicationLinkSnapshot link = link(
                new AttrMappingSnapshot("mail", "rfc822Mailbox", "${value}"),
                new AttrMappingSnapshot("uid", "userId", "u-${value}"));
        Map<String, Object> raw = Map.of("attributes", Map.of(
                "uid", List.of("alice"),
                "mail", List.of("alice@x.com")));

        Map<String, Object> payload = ReplicationPayloadMapper.buildMappedPayload(
                ReplicationOperationType.ADD, raw, link, correlationId);

        Map<String, List<String>> attrs = (Map<String, List<String>>) payload.get("attributes");
        assertThat(attrs).containsEntry("userId", List.of("u-alice"));
        assertThat(attrs).containsEntry("rfc822Mailbox", List.of("alice@x.com"));
        assertThat(payload).containsKey(ReplicationPayloadCodec.CORRELATION_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void modify_renamesNameAndTransformsValues_preservingType() {
        ReplicationLinkSnapshot link = link(
                new AttrMappingSnapshot("mail", "rfc822Mailbox", "${value}"));
        Map<String, Object> raw = Map.of("modifications", List.of(
                Map.of("type", "REPLACE", "name", "mail", "values", List.of("a@x.com", "b@x.com"))));

        Map<String, Object> payload = ReplicationPayloadMapper.buildMappedPayload(
                ReplicationOperationType.MODIFY, raw, link, null);

        List<Map<String, Object>> mods = (List<Map<String, Object>>) payload.get("modifications");
        assertThat(mods).hasSize(1);
        assertThat(mods.get(0)).containsEntry("type", "REPLACE")
                .containsEntry("name", "rfc822Mailbox")
                .containsEntry("values", List.of("a@x.com", "b@x.com"));
        // No correlationId passed → none in the payload.
        assertThat(payload).doesNotContainKey(ReplicationPayloadCodec.CORRELATION_ID);
    }

    @Test
    void delete_emptyPayloadBesidesCorrelation() {
        Map<String, Object> payload = ReplicationPayloadMapper.buildMappedPayload(
                ReplicationOperationType.DELETE, Map.of(), link(), correlationId);
        assertThat(payload).containsOnlyKeys(ReplicationPayloadCodec.CORRELATION_ID);
    }

    @Test
    void modifyDn_partsPassThroughUnchanged() {
        Map<String, Object> raw = Map.of(
                "newRdn", "uid=jane",
                "deleteOldRdn", true,
                "newSuperiorDn", "ou=new,dc=test");

        Map<String, Object> payload = ReplicationPayloadMapper.buildMappedPayload(
                ReplicationOperationType.MODIFY_DN, raw, link(), null);

        assertThat(payload).containsEntry("newRdn", "uid=jane")
                .containsEntry("deleteOldRdn", true)
                .containsEntry("newSuperiorDn", "ou=new,dc=test");
    }

    private static ReplicationLinkSnapshot link(AttrMappingSnapshot... mappings) {
        return new ReplicationLinkSnapshot(
                UUID.randomUUID(), "L", null, null, null, null, true, false, List.of(mappings));
    }
}
