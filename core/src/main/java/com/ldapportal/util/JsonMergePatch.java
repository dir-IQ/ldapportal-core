// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Minimal RFC 7386 (JSON Merge Patch) implementation — enough for the user
 * preferences document, without pulling in an extra dependency.
 *
 * <p>The rule, applied recursively: if the patch is an object, walk its keys;
 * a {@code null} value removes that key from the target, an object value
 * merges into the matching target object (recursively), and any other value
 * replaces the target key outright. If the patch is not an object, it replaces
 * the target wholesale.</p>
 *
 * <p>This lets the frontend write a single namespace — {@code {"appearance":
 * {"theme":"dark"}}} — without read-modify-writing the whole document, so two
 * tabs editing different namespaces don't clobber each other.</p>
 */
public final class JsonMergePatch {

    private JsonMergePatch() {}

    /**
     * Merge {@code patch} into {@code target}, mutating and returning
     * {@code target} when both are objects, otherwise returning {@code patch}.
     */
    public static JsonNode apply(JsonNode target, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            return patch;
        }
        // Target isn't an object (null, or a scalar being replaced by an
        // object) — start from an empty object so the patch's keys land.
        ObjectNode targetObj = (target != null && target.isObject())
                ? (ObjectNode) target
                : JsonNodeFactory.instance.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (value.isNull()) {
                targetObj.remove(key);
            } else if (value.isObject()) {
                targetObj.set(key, apply(targetObj.get(key), value));
            } else {
                targetObj.set(key, value);
            }
        }
        return targetObj;
    }
}
