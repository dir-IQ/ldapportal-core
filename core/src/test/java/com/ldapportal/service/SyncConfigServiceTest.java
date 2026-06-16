// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.SyncTransformRule;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.ldap.sync.MembershipReconciler;
import com.ldapportal.ldap.sync.RecomputeEnqueuer;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transform-rule validation + normalization on the sync-set config path. The
 * engine matches first-wins on a non-blank sourceAttr and understands only the
 * single {@code ${value}} template token, so the service rejects config that
 * couldn't work and canonicalizes what it stores.
 */
@ExtendWith(MockitoExtension.class)
class SyncConfigServiceTest {

    @Mock private SyncLinkRepository linkRepo;
    @Mock private SyncSetRepository setRepo;
    @Mock private MembershipRepository membershipRepo;
    @Mock private DirectoryConnectionRepository directoryRepo;
    @Mock private MembershipReconciler reconciler;
    @Mock private RecomputeEnqueuer enqueuer;

    private SyncConfigService service;

    @BeforeEach
    void setUp() {
        service = new SyncConfigService(linkRepo, setRepo, membershipRepo, directoryRepo, reconciler, enqueuer);
    }

    private SyncSetRequest req(List<SyncTransformRule> rules) {
        return new SyncSetRequest(UUID.randomUUID(), "people", null, null, null, null, null, null, null,
                SyncDeletePolicy.DELETE, rules, null, null, true);
    }

    @Test
    void rejectsBlankSourceAttr() {
        when(linkRepo.existsById(any())).thenReturn(true);
        assertThatThrownBy(() -> service.createSet(req(List.of(new SyncTransformRule("  ", "x", null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceAttr is required");
    }

    @Test
    void rejectsDuplicateSourceAttr() {
        when(linkRepo.existsById(any())).thenReturn(true);
        assertThatThrownBy(() -> service.createSet(req(List.of(
                new SyncTransformRule("uid", "sAMAccountName", null),
                new SyncTransformRule("UID", "x", null)))))  // same source, different case
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectsUnknownTemplateToken() {
        when(linkRepo.existsById(any())).thenReturn(true);
        assertThatThrownBy(() -> service.createSet(req(List.of(
                new SyncTransformRule("cn", "cn", "${other}")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only ${value}");
    }

    @Test
    void normalizesValidRules() {
        when(linkRepo.existsById(any())).thenReturn(true);
        when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createSet(req(List.of(
                new SyncTransformRule(" uid ", " sAMAccountName ", null),  // trimmed
                new SyncTransformRule("cn", "  ", "Mr ${value}"))));        // blank target → null

        ArgumentCaptor<SyncSet> cap = ArgumentCaptor.forClass(SyncSet.class);
        verify(setRepo).save(cap.capture());
        List<SyncTransformRule> stored = cap.getValue().getTransformRules();

        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getSourceAttr()).isEqualTo("uid");
        assertThat(stored.get(0).getTargetAttr()).isEqualTo("sAMAccountName");
        assertThat(stored.get(1).getTargetAttr()).isNull();
        assertThat(stored.get(1).getValueTemplate()).isEqualTo("Mr ${value}");
    }

    @Test
    void emptyRuleListStoresNull() {
        when(linkRepo.existsById(any())).thenReturn(true);
        when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createSet(req(List.of()));

        ArgumentCaptor<SyncSet> cap = ArgumentCaptor.forClass(SyncSet.class);
        verify(setRepo).save(cap.capture());
        assertThat(cap.getValue().getTransformRules()).isNull();
    }

    @Test
    void listMemberships_lowercasesAndWrapsSearchTerm() {
        when(setRepo.findById(any())).thenReturn(Optional.of(new SyncSet()));
        when(membershipRepo.search(any(), any(), any(), any())).thenReturn(Page.empty());

        service.listMemberships(UUID.randomUUID(), MembershipState.FAILED, "  Alice  ",
                PageRequest.of(0, 50));

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(membershipRepo).search(any(), eq(MembershipState.FAILED), q.capture(), any());
        assertThat(q.getValue()).isEqualTo("%alice%");
    }

    @Test
    void listMemberships_blankSearchTermBecomesNull() {
        when(setRepo.findById(any())).thenReturn(Optional.of(new SyncSet()));
        when(membershipRepo.search(any(), any(), any(), any())).thenReturn(Page.empty());

        service.listMemberships(UUID.randomUUID(), null, "   ", PageRequest.of(0, 50));

        verify(membershipRepo).search(any(), isNull(), isNull(), any());
    }
}
