// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.entity.UserPreferences;
import com.ldapportal.repository.UserPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UserPreferencesServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private UserPreferencesRepository repo;
    private UserPreferencesService service;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(UserPreferencesRepository.class);
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        given(txm.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        service = new UserPreferencesService(repo, mapper, txm);
        // Saving returns the same entity (the service reads back from it).
        given(repo.saveAndFlush(any(UserPreferences.class)))
                .willAnswer(inv -> inv.getArgument(0));
    }

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void mergePatchCreatesRowAndStampsSchemaVersion() throws Exception {
        given(repo.findById(accountId)).willReturn(Optional.empty());

        Map<String, Object> doc = service.applyMergePatch(accountId,
                json("{\"appearance\":{\"theme\":\"dark\"}}"));

        assertThat(doc).containsEntry("schemaVersion", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> appearance = (Map<String, Object>) doc.get("appearance");
        assertThat(appearance).containsEntry("theme", "dark");
    }

    @Test
    void mergePatchPreservesOtherNamespaces() throws Exception {
        UserPreferences existing = new UserPreferences();
        existing.setAccountId(accountId);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("appearance", new LinkedHashMap<>(Map.of("theme", "dark")));
        existing.setDocument(stored);
        given(repo.findById(accountId)).willReturn(Optional.of(existing));

        Map<String, Object> doc = service.applyMergePatch(accountId,
                json("{\"sidebar\":{\"collapsed\":true}}"));

        assertThat(doc).containsKey("appearance");
        assertThat(doc).containsKey("sidebar");
    }

    @Test
    void unknownNamespaceRejected() throws Exception {
        assertThatThrownBy(() -> service.applyMergePatch(accountId, json("{\"bogus\":{}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown preferences namespace");
    }

    @Test
    void panelsNamespaceAccepted() throws Exception {
        given(repo.findById(accountId)).willReturn(Optional.empty());

        Map<String, Object> doc = service.applyMergePatch(accountId,
                json("{\"panels\":{\"browser-group-members\":320}}"));

        assertThat(doc).containsKey("panels");
    }

    @Test
    void nonObjectPatchRejected() throws Exception {
        assertThatThrownBy(() -> service.applyMergePatch(accountId, json("[1,2,3]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test
    void oversizeDocumentRejected() throws Exception {
        given(repo.findById(accountId)).willReturn(Optional.empty());
        String big = "x".repeat(300 * 1024);
        assertThatThrownBy(() -> service.applyMergePatch(accountId,
                json("{\"search\":{\"directory\":{\"blob\":\"" + big + "\"}}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void replaceNamespaceOverwritesSubtree() throws Exception {
        UserPreferences existing = new UserPreferences();
        existing.setAccountId(accountId);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("tables", new LinkedHashMap<>(Map.of("audit", Map.of("pageSize", 25))));
        existing.setDocument(stored);
        given(repo.findById(accountId)).willReturn(Optional.of(existing));

        Map<String, Object> doc = service.replaceNamespace(accountId, "tables",
                json("{\"reports\":{\"pageSize\":100}}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> tables = (Map<String, Object>) doc.get("tables");
        assertThat(tables).containsKey("reports").doesNotContainKey("audit");
    }

    @Test
    void clearNamespaceRemovesIt() throws Exception {
        UserPreferences existing = new UserPreferences();
        existing.setAccountId(accountId);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("appearance", new LinkedHashMap<>(Map.of("theme", "dark")));
        stored.put("sidebar", new LinkedHashMap<>(Map.of("collapsed", true)));
        existing.setDocument(stored);
        given(repo.findById(accountId)).willReturn(Optional.of(existing));

        Map<String, Object> doc = service.clearNamespace(accountId, "sidebar");

        assertThat(doc).containsKey("appearance").doesNotContainKey("sidebar");
    }

    @Test
    void appearanceDefaultsWhenAbsent() {
        given(repo.findById(accountId)).willReturn(Optional.empty());
        UserPreferencesService.Appearance a = service.appearance(accountId);
        assertThat(a.theme()).isEqualTo("light");
        assertThat(a.density()).isEqualTo("comfortable");
    }

    @Test
    void appearanceReadsStoredValues() {
        UserPreferences existing = new UserPreferences();
        existing.setAccountId(accountId);
        existing.setDocument(new LinkedHashMap<>(Map.of(
                "appearance", new LinkedHashMap<>(Map.of("theme", "dark", "density", "compact")))));
        given(repo.findById(accountId)).willReturn(Optional.of(existing));

        UserPreferencesService.Appearance a = service.appearance(accountId);
        assertThat(a.theme()).isEqualTo("dark");
        assertThat(a.density()).isEqualTo("compact");
    }

    @Test
    void retriesOnOptimisticLockConflict() throws Exception {
        given(repo.findById(accountId)).willReturn(Optional.empty());
        // First save conflicts, second succeeds.
        given(repo.saveAndFlush(any(UserPreferences.class)))
                .willThrow(new ObjectOptimisticLockingFailureException(UserPreferences.class, accountId))
                .willAnswer(inv -> inv.getArgument(0));

        Map<String, Object> doc = service.applyMergePatch(accountId,
                json("{\"appearance\":{\"theme\":\"dark\"}}"));

        assertThat(doc).containsKey("appearance");
        verify(repo, times(2)).saveAndFlush(any(UserPreferences.class));
    }
}
