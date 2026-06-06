// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ldapportal.entity.UserPreferences;
import com.ldapportal.repository.UserPreferencesRepository;
import com.ldapportal.util.JsonMergePatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes the per-account preferences document — the single home for
 * every UI customization the user makes (see {@link UserPreferences}).
 *
 * <p>Writes are <em>partial</em>: callers send only the namespace subtree they
 * touched and the server merge-patches it into the stored document
 * ({@link #applyMergePatch}). Concurrent writes to different namespaces never
 * collide; a rare same-namespace race is resolved by the optimistic-lock
 * retry rather than a silent clobber. The JSON within each namespace is opaque
 * to the server — the frontend owns it — but we guard the top-level namespace
 * set and the overall document size so a buggy or hostile client can't bloat
 * or pollute the row.</p>
 */
@Service
@Slf4j
public class UserPreferencesService {

    /** Top-level namespaces the document is allowed to carry. */
    static final Set<String> NAMESPACES = Set.of(
            "appearance", "tables", "filters", "search", "modals", "sidebar");

    /** Document version marker, written alongside any first write. */
    private static final String SCHEMA_VERSION_KEY = "schemaVersion";

    /** Guard against unbounded growth — a preferences blob has no business
     *  being large. Comfortably fits many tables, filters, and history. */
    private static final int MAX_DOCUMENT_BYTES = 256 * 1024;

    private static final int MAX_WRITE_ATTEMPTS = 4;

    private static final String DEFAULT_THEME = "light";
    private static final String DEFAULT_DENSITY = "comfortable";

    private final UserPreferencesRepository repo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public UserPreferencesService(UserPreferencesRepository repo,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager txManager) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /** The full document for an account, or an empty map when none is stored. */
    public Map<String, Object> get(UUID accountId) {
        return txTemplate.execute(status -> repo.findById(accountId)
                .map(UserPreferences::getDocument)
                .orElseGet(LinkedHashMap::new));
    }

    /**
     * Resolved appearance (theme + density) with defaults applied. Used by
     * {@code /auth/me} and to seed the pre-paint FOUC hint cookie at login.
     */
    public Appearance appearance(UUID accountId) {
        Map<String, Object> doc = get(accountId);
        Object appearance = doc.get("appearance");
        String theme = DEFAULT_THEME;
        String density = DEFAULT_DENSITY;
        if (appearance instanceof Map<?, ?> m) {
            if (m.get("theme") instanceof String t && !t.isBlank()) theme = t;
            if (m.get("density") instanceof String d && !d.isBlank()) density = d;
        }
        return new Appearance(theme, density);
    }

    public record Appearance(String theme, String density) {}

    /**
     * Merge-patch (RFC 7386) the given partial document into the account's
     * stored document. Top-level keys must be known namespaces; a {@code null}
     * value within the patch deletes that key. Retries on a concurrent-write
     * conflict against a fresh read.
     *
     * @return the full document after the merge
     */
    public Map<String, Object> applyMergePatch(UUID accountId, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw new IllegalArgumentException("Preferences patch must be a JSON object");
        }
        validateNamespaces(patch);
        return writeWithRetry(accountId, current -> JsonMergePatch.apply(current, patch));
    }

    /** Replace one namespace's subtree wholesale (PUT semantics). */
    public Map<String, Object> replaceNamespace(UUID accountId, String namespace, JsonNode value) {
        requireKnownNamespace(namespace);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Namespace body is required");
        }
        return writeWithRetry(accountId, current -> {
            current.set(namespace, value);
            return current;
        });
    }

    /** Remove one namespace, resetting it to defaults (DELETE semantics). */
    public Map<String, Object> clearNamespace(UUID accountId, String namespace) {
        requireKnownNamespace(namespace);
        return writeWithRetry(accountId, current -> {
            current.remove(namespace);
            return current;
        });
    }

    // ── internals ────────────────────────────────────────────────────────────

    private interface DocumentMutation {
        /** Apply the change to {@code current} and return the new root. */
        JsonNode apply(ObjectNode current);
    }

    private Map<String, Object> writeWithRetry(UUID accountId, DocumentMutation mutation) {
        ObjectOptimisticLockingFailureException last = null;
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                return txTemplate.execute(status -> writeOnce(accountId, mutation));
            } catch (ObjectOptimisticLockingFailureException ex) {
                last = ex;
                log.debug("preferences write contention for {} (attempt {}/{})",
                        accountId, attempt, MAX_WRITE_ATTEMPTS);
            }
        }
        throw last;
    }

    private Map<String, Object> writeOnce(UUID accountId, DocumentMutation mutation) {
        UserPreferences entity = repo.findById(accountId).orElseGet(() -> {
            UserPreferences fresh = new UserPreferences();
            fresh.setAccountId(accountId);
            fresh.setDocument(new LinkedHashMap<>());
            return fresh;
        });

        ObjectNode current = toObjectNode(entity.getDocument());
        JsonNode merged = mutation.apply(current);
        if (!merged.isObject()) {
            // Shouldn't happen — every mutation keeps an object root — but be
            // defensive rather than persist a scalar document.
            throw new IllegalArgumentException("Preferences document must be a JSON object");
        }
        ObjectNode root = (ObjectNode) merged;
        if (!root.has(SCHEMA_VERSION_KEY)) {
            root.put(SCHEMA_VERSION_KEY, 1);
        }
        enforceSize(root);

        entity.setDocument(objectMapper.convertValue(root, new TypeReference<>() {}));
        repo.saveAndFlush(entity);
        return entity.getDocument();
    }

    private ObjectNode toObjectNode(Map<String, Object> document) {
        if (document == null || document.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        JsonNode node = objectMapper.valueToTree(document);
        return node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
    }

    private void validateNamespaces(JsonNode patch) {
        patch.fieldNames().forEachRemaining(name -> {
            if (!SCHEMA_VERSION_KEY.equals(name) && !NAMESPACES.contains(name)) {
                throw new IllegalArgumentException("Unknown preferences namespace: " + name);
            }
        });
    }

    private void requireKnownNamespace(String namespace) {
        if (!NAMESPACES.contains(namespace)) {
            throw new IllegalArgumentException("Unknown preferences namespace: " + namespace);
        }
    }

    private void enforceSize(JsonNode root) {
        int bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException(
                    "Preferences document too large (" + bytes + " bytes; max " + MAX_DOCUMENT_BYTES + ")");
        }
    }
}
