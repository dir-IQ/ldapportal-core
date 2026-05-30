// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DeleteRequest;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ModifyDNRequest;
import com.unboundid.ldap.sdk.ModifyRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decodes the JSONB {@code payload} on a {@link com.ldapportal.entity.ReplicationEvent}
 * back into an UnboundID SDK request. The wire-stable shape is fixed
 * in §9.2 of the directory-sync design plan:
 *
 * <pre>
 *   ADD:       { "attributes": { attrName: [v1, v2, ...], ... } }
 *   MODIFY:    { "modifications": [
 *                  { "type": "REPLACE|ADD|DELETE",
 *                    "name": targetAttrName,
 *                    "values": [v1, ...] }, ... ] }
 *   DELETE:    {}    (DN alone identifies the operation)
 *   MODIFY_DN: { "newRdn": "...",
 *                "deleteOldRdn": true|false,
 *                "newSuperiorDn": "..." | null }
 * </pre>
 *
 * <p>The codec is split from {@link ReplicationEnqueuer} on purpose:
 * the enqueuer writes the shape, the worker (P1) reads it, and the
 * test surface needs to verify the round-trip without an enqueuer or
 * a database. Static methods only — no state.
 *
 * <p>Values are already DN- and value-mapped at enqueue time, so the
 * worker uses the decoded request as-is against the target.
 */
public final class ReplicationPayloadCodec {

    private ReplicationPayloadCodec() {}

    public static AddRequest decodeAdd(String targetDn, Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, List<String>> attrs =
                (Map<String, List<String>>) payload.getOrDefault("attributes", Map.of());
        List<Attribute> attributes = new ArrayList<>(attrs.size());
        for (Map.Entry<String, List<String>> e : attrs.entrySet()) {
            attributes.add(new Attribute(e.getKey(), e.getValue().toArray(new String[0])));
        }
        return new AddRequest(targetDn, attributes);
    }

    public static ModifyRequest decodeModify(String targetDn, Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mods =
                (List<Map<String, Object>>) payload.getOrDefault("modifications", List.of());
        List<Modification> modifications = new ArrayList<>(mods.size());
        for (Map<String, Object> m : mods) {
            String typeName = (String) m.get("type");
            String name     = (String) m.get("name");
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) m.getOrDefault("values", List.of());
            modifications.add(new Modification(
                    parseType(typeName), name, values.toArray(new String[0])));
        }
        return new ModifyRequest(targetDn, modifications);
    }

    public static DeleteRequest decodeDelete(String targetDn) {
        return new DeleteRequest(targetDn);
    }

    public static ModifyDNRequest decodeModifyDn(String targetDn, Map<String, Object> payload) {
        String newRdn        = (String)  payload.get("newRdn");
        Boolean deleteOldRdn = (Boolean) payload.get("deleteOldRdn");
        String newSuperior   = (String)  payload.get("newSuperiorDn");
        if (newSuperior == null) {
            return new ModifyDNRequest(targetDn, newRdn, deleteOldRdn != null && deleteOldRdn);
        }
        return new ModifyDNRequest(targetDn, newRdn,
                deleteOldRdn != null && deleteOldRdn, newSuperior);
    }

    private static ModificationType parseType(String name) {
        // The JSONB stores enum names verbatim (REPLACE / ADD / DELETE /
        // INCREMENT). UnboundID's ModificationType has a fromName helper
        // for the LDAP wire form ("replace" etc.); we encode upper-case
        // here so case-insensitive lookup is the safer call.
        if (name == null) return ModificationType.REPLACE;
        return switch (name.toUpperCase()) {
            case "ADD"       -> ModificationType.ADD;
            case "DELETE"    -> ModificationType.DELETE;
            case "INCREMENT" -> ModificationType.INCREMENT;
            default          -> ModificationType.REPLACE;
        };
    }
}
