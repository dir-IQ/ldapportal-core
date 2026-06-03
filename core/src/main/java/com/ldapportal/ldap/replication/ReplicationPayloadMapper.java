// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationOperationType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps a <em>raw</em> (pre-mapping) replication payload to the final,
 * DN/attribute-mapped payload persisted on a {@code replication_events} row.
 * Pure and static, mirroring {@link DnMapper} / {@link AttributeMapper}.
 *
 * <p>Both capture paths converge here so their queue rows are byte-identical:
 * <ul>
 *   <li>the live {@link ReplicationEnqueuer} converts its {@code CapturedWrite}
 *       to the raw shape, then calls {@link #buildMappedPayload};</li>
 *   <li>the changelog poller hands the {@code ChangelogChange.rawPayload} the
 *       OUD parser already produced in that exact shape.</li>
 * </ul>
 *
 * <p>The <b>raw</b> payload shape (keys by operation):
 * <ul>
 *   <li>{@code ADD}       — {@code attributes}: Map&lt;String,List&lt;String&gt;&gt; (raw names/values)</li>
 *   <li>{@code MODIFY}    — {@code modifications}: List&lt;Map&gt; {@code {type, name(raw), values(raw)}}</li>
 *   <li>{@code DELETE}    — empty</li>
 *   <li>{@code MODIFY_DN} — {@code newRdn}, {@code deleteOldRdn}, {@code newSuperiorDn}</li>
 * </ul>
 * Attribute rename + value templating ({@link AttributeMapper}) is applied to
 * ADD attributes and MODIFY names/values; MODIFY_DN parts pass through (v1 does
 * not rewrite RDN attributes). See design §6.3.
 */
public final class ReplicationPayloadMapper {

    private ReplicationPayloadMapper() {
    }

    /**
     * Build the final mapped payload from a raw payload.
     *
     * @param correlationId source-side trace id to ride along (placed first),
     *                      or {@code null} to omit.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildMappedPayload(ReplicationOperationType operation,
                                                         Map<String, Object> rawPayload,
                                                         ReplicationLinkSnapshot link,
                                                         UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (correlationId != null) {
            payload.put(ReplicationPayloadCodec.CORRELATION_ID, correlationId.toString());
        }
        switch (operation) {
            case ADD -> {
                Map<String, List<String>> rawAttrs =
                        (Map<String, List<String>>) rawPayload.getOrDefault("attributes", Map.of());
                payload.put("attributes", AttributeMapper.mapAttributes(rawAttrs, link));
            }
            case MODIFY -> {
                List<Map<String, Object>> rawMods =
                        (List<Map<String, Object>>) rawPayload.getOrDefault("modifications", List.of());
                payload.put("modifications", mappedModifications(rawMods, link));
            }
            case DELETE -> { /* empty payload — DN alone identifies the operation */ }
            case MODIFY_DN -> {
                payload.put("newRdn", rawPayload.get("newRdn"));
                payload.put("deleteOldRdn", rawPayload.get("deleteOldRdn"));
                payload.put("newSuperiorDn", rawPayload.get("newSuperiorDn"));
            }
        }
        return payload;
    }

    /** Apply attribute rename + value templating to a list of raw modifications. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mappedModifications(List<Map<String, Object>> rawMods,
                                                                 ReplicationLinkSnapshot link) {
        List<Map<String, Object>> out = new ArrayList<>(rawMods.size());
        for (Map<String, Object> raw : rawMods) {
            AttributeMapper.Mapping mapping =
                    AttributeMapper.mappingFor((String) raw.get("name"), link);
            List<String> rawValues = (List<String>) raw.getOrDefault("values", List.of());
            List<String> transformed = new ArrayList<>(rawValues.size());
            for (String v : rawValues) {
                transformed.add(mapping.valueTransform().apply(v));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", raw.get("type"));
            entry.put("name", mapping.targetAttr());
            entry.put("values", transformed);
            out.add(entry);
        }
        return out;
    }
}
