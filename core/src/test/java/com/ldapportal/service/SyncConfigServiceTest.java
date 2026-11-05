// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.sync.SyncDeleteApproval;
import com.ldapportal.dto.sync.SyncLinkRequest;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncRecheckResult;
import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.dto.sync.SyncVerifyResult;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.SyncTransformRule;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncChangelogHealth;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.entity.enums.SyncLoggingLevel;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.ldap.sync.MembershipReconciler;
import com.ldapportal.ldap.sync.RecomputeEnqueuer;
import com.ldapportal.ldap.sync.SyncContentVerifier;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private com.ldapportal.ldap.sync.SyncVerifyProgressTracker verifyProgress;
    @Mock private MembershipReconciler reconciler;
    @Mock private SyncContentVerifier verifier;
    @Mock private RecomputeEnqueuer enqueuer;
    @Mock private com.ldapportal.ldap.sync.SyncPreviewService previewService;
    @Mock private com.ldapportal.ldap.sync.SyncReconcileHistory reconcileHistory;
    @Mock private com.ldapportal.ldap.sync.RecomputeEngine engine;
    @Mock private AuditService auditService;
    @Mock private com.ldapportal.repository.SyncReconcileRunRepository reconcileRunRepo;
    @Mock private com.ldapportal.ldap.sync.SyncDriftStore driftStore;

    private SyncConfigService service;

    @BeforeEach
    void setUp() {
        service = new SyncConfigService(linkRepo, setRepo, membershipRepo, directoryRepo,
                reconciler, verifier, enqueuer, previewService, reconcileHistory, engine, auditService,
                reconcileRunRepo, verifyProgress, driftStore,
                new com.ldapportal.ldap.sync.SyncVerifySnapshot(setRepo, driftStore));
    }

    private SyncSetRequest req(List<SyncTransformRule> rules) {
        return new SyncSetRequest(UUID.randomUUID(), "people", "ou=people,dc=src,dc=com", null, null, null,
                null, null, null, SyncDeletePolicy.DELETE, rules, null, null, true, null);
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
    void loggingLevel_isSetWhenGiven_summaryOnCreate_andLeftAloneWhenNull() {
        when(linkRepo.existsById(any())).thenReturn(true);
        when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SyncSetRequest base = req(List.of());
        SyncSetRequest detailed = new SyncSetRequest(base.linkId(), base.name(), base.objectScopeBaseDn(), null,
                null, null, null, null, null, base.deletePolicy(), null, null, null, true, SyncLoggingLevel.DETAILED);

        assertThat(service.createSet(detailed).loggingLevel()).isEqualTo(SyncLoggingLevel.DETAILED);
        assertThat(service.createSet(base).loggingLevel()).isEqualTo(SyncLoggingLevel.SUMMARY);

        UUID setId = UUID.randomUUID();
        SyncSet existing = new SyncSet();
        existing.setLoggingLevel(SyncLoggingLevel.DETAILED);
        when(setRepo.findById(setId)).thenReturn(Optional.of(existing));
        assertThat(service.updateSet(setId, base).loggingLevel()).isEqualTo(SyncLoggingLevel.DETAILED); // null: unchanged
    }

    @Test
    void applicabilityFilter_laidOutOverSeveralLines_isStoredAsOneLine() {
        when(linkRepo.existsById(any())).thenReturn(true);
        when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SyncSetRequest base = req(List.of());
        SyncSetRequest multiline = new SyncSetRequest(base.linkId(), base.name(), base.objectScopeBaseDn(), null,
                null, null, "(&(objectClass=secGroup)\n  (!(|(cn=iv-admin)\r\n      (cn=John Smith))))\n", null, null,
                base.deletePolicy(), null, null, null, true, null);

        assertThat(service.createSet(multiline).applicabilityFilter())
                .isEqualTo("(&(objectClass=secGroup)(!(|(cn=iv-admin)(cn=John Smith))))");
    }

    @Test
    void sourceBaseDn_isRequired() {
        when(linkRepo.existsById(any())).thenReturn(true);
        SyncSetRequest base = req(List.of());
        SyncSetRequest blank = new SyncSetRequest(base.linkId(), base.name(), "  ", null, null, null, null, null,
                null, base.deletePolicy(), null, null, null, true, null);

        assertThatThrownBy(() -> service.createSet(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source base DN is required.");
        verify(setRepo, never()).save(any());
    }

    @Test
    void writeRunEntriesCsv_writesOneQuotedRowPerEntry() {
        UUID setId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<com.ldapportal.ldap.sync.SyncRunEntryStore.Entry> sink = inv.getArgument(2);
            sink.accept(new com.ldapportal.ldap.sync.SyncRunEntryStore.Entry(0, "ADD", "uid=a,dc=dst", null));
            sink.accept(new com.ldapportal.ldap.sync.SyncRunEntryStore.Entry(1, "ERROR", "uid=b,dc=dst",
                    "apply failed: \"quoted\""));
            return null;
        }).when(reconcileHistory).entries(eq(setId), eq(runId), any());
        java.io.StringWriter out = new java.io.StringWriter();

        service.writeRunEntriesCsv(setId, runId, out);

        assertThat(out.toString()).isEqualTo("\"outcome\",\"dn\",\"reason\"\r\n"
                + "\"ADD\",\"uid=a,dc=dst\",\"\"\r\n"
                + "\"ERROR\",\"uid=b,dc=dst\",\"apply failed: \"\"quoted\"\"\"\r\n");
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
    void listSets_countsHeldDeletesAmongReviewRows() {
        UUID setId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(setId);
        when(setRepo.findAll()).thenReturn(List.of(set));
        com.ldapportal.repository.MembershipStateCount review =
                org.mockito.Mockito.mock(com.ldapportal.repository.MembershipStateCount.class);
        when(review.getSyncSetId()).thenReturn(setId);
        when(review.getState()).thenReturn(MembershipState.REVIEW);
        when(review.getCnt()).thenReturn(5L);
        when(membershipRepo.countGroupedByState()).thenReturn(List.of(review));
        when(membershipRepo.countHeldDeletesBySet("scope-exit held for review%"))
                .thenReturn(List.<Object[]>of(new Object[]{setId, 3L}));

        var out = service.listSets(null);

        assertThat(out.get(0).stateCounts())
                .containsEntry("REVIEW", 5L)
                .containsEntry(SyncConfigService.HELD_DELETE_KEY, 3L);
    }

    @Test
    void verifyContents_persistsDriftSnapshot_whenScanComplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(
                10, 9, 7, 2, 1, 0,
                List.of(), List.of(), List.of(),
                true, true, null)); // complete scan: missing=2, orphan=1, mismatch=0

        SyncVerifyResult out = service.verifyContents(setId);

        assertThat(out.missingOnTarget()).isEqualTo(2);
        // The drift counts are cached on the set so the dashboard can read them cheaply.
        verify(setRepo).recordVerifyResult(eq(setId), any(), eq(2), eq(1), eq(0));
        // Every item replaces the set's stored ones (only the latest verify is kept).
        verify(driftStore).replace(eq(setId), eq(out.items()));
        // No held references counted (none in the result): nothing stored for them.
        verify(setRepo, never()).recordVerifyHeld(any(), anyInt(), anyInt());
    }

    @Test
    void verifyContents_storesHeldReferenceCounts_whenScanComplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(10, 10, 10, 0, 0, 0,
                List.of(), List.of(), List.of(), true, true, null, true,
                new SyncVerifyResult.HeldReferences(4, 3, false, List.of())));

        service.verifyContents(setId);

        verify(setRepo).recordVerifyHeld(setId, 4, 3);
    }

    @Test
    void verifyContents_skipsPersist_whenScanIncomplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(
                0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(),
                false, true, null)); // partial: source enumeration failed

        service.verifyContents(setId);

        // A partial scan must not overwrite a previously good snapshot with low counts...
        verify(setRepo, never()).recordVerifyResult(any(), any(), anyInt(), anyInt(), anyInt());
        // ...nor replace the stored items with a partial list...
        verify(driftStore, never()).replace(any(), any());
        // ...but it's noted, so the UI can say the snapshot may be out of date.
        verify(setRepo).recordVerifyIncomplete(eq(setId), any());
    }

    @Test
    void drift_pagesTheStoredItems_andSaysWhenTheCountsPredateThem() {
        UUID setId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setLastVerifiedAt(java.time.OffsetDateTime.now());
        set.setVerifyMissingCount(2);
        set.setVerifyOrphanCount(0);
        set.setVerifyMismatchCount(0);
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        var none = new java.util.EnumMap<com.ldapportal.entity.enums.SyncDriftCategory, Long>(
                com.ldapportal.entity.enums.SyncDriftCategory.class);
        for (var c : com.ldapportal.entity.enums.SyncDriftCategory.values()) none.put(c, 0L);
        when(driftStore.countsByCategory(setId)).thenReturn(none);

        // Counts on the set, nothing stored: from a verify before items were kept.
        assertThat(service.drift(setId, null, null, 0, 50).detailsRecorded()).isFalse();

        var stored = new java.util.EnumMap<>(none);
        stored.put(com.ldapportal.entity.enums.SyncDriftCategory.MISSING, 2L);
        when(driftStore.countsByCategory(setId)).thenReturn(stored);
        when(driftStore.count(setId, com.ldapportal.entity.enums.SyncDriftCategory.MISSING, "jo")).thenReturn(1L);
        when(driftStore.page(setId, com.ldapportal.entity.enums.SyncDriftCategory.MISSING, "jo", 400, 200))
                .thenReturn(List.of(new com.ldapportal.ldap.sync.SyncDriftStore.Row(3,
                        new com.ldapportal.dto.sync.SyncDriftItem(com.ldapportal.entity.enums.SyncDriftCategory.MISSING,
                                "uid=jo,dc=t", "uid=jo,dc=s", "jo", List.of()))));

        var page = service.drift(setId, com.ldapportal.entity.enums.SyncDriftCategory.MISSING, "jo", 2, 999);

        assertThat(page.detailsRecorded()).isTrue();
        assertThat(page.size()).isEqualTo(200); // clamped
        assertThat(page.matched()).isEqualTo(1);
        assertThat(page.entries()).singleElement().satisfies(e -> {
            assertThat(e.seq()).isEqualTo(3);
            assertThat(e.sourceDn()).isEqualTo("uid=jo,dc=s");
        });
    }

    @Test
    void driftCsv_writesARowPerDifferingAttribute_hidesSecrets_andNeutralisesFormulas() throws Exception {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<com.ldapportal.ldap.sync.SyncDriftStore.Row> sink = inv.getArgument(1);
            sink.accept(new com.ldapportal.ldap.sync.SyncDriftStore.Row(0, new com.ldapportal.dto.sync.SyncDriftItem(
                    com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN, "uid=old,dc=t", null, null, List.of())));
            sink.accept(new com.ldapportal.ldap.sync.SyncDriftStore.Row(1, new com.ldapportal.dto.sync.SyncDriftItem(
                    com.ldapportal.entity.enums.SyncDriftCategory.MISMATCH, "uid=jo,dc=t", "uid=jo,dc=s", "jo", List.of(
                    new com.ldapportal.dto.sync.SyncDriftItem.AttributeDiff("title", List.of("=HYPERLINK(1)"),
                            List.of("Eng", "Lead"), false, false),
                    new com.ldapportal.dto.sync.SyncDriftItem.AttributeDiff("userPassword", List.of(), List.of(),
                            true, false)))));
            return null;
        }).when(driftStore).forEach(eq(setId), any());
        java.io.StringWriter out = new java.io.StringWriter();

        service.writeDriftCsv(setId, out);

        assertThat(out.toString()).isEqualTo(
                "\"category\",\"targetDn\",\"sourceDn\",\"identity\",\"attribute\",\"expected\",\"actual\"\r\n"
                + "\"ORPHAN\",\"uid=old,dc=t\",\"\",\"\",\"\",\"\",\"\"\r\n"
                + "\"MISMATCH\",\"uid=jo,dc=t\",\"uid=jo,dc=s\",\"jo\",\"title\",\"'=HYPERLINK(1)\",\"Eng|Lead\"\r\n"
                + "\"MISMATCH\",\"uid=jo,dc=t\",\"uid=jo,dc=s\",\"jo\",\"userPassword\",\"(hidden)\",\"(hidden)\"\r\n");
    }

    // ── Drift repair ────────────────────────────────────────────────────────

    private static com.ldapportal.ldap.sync.SyncDriftStore.Row driftRow(int seq,
            com.ldapportal.entity.enums.SyncDriftCategory c, String uid) {
        return new com.ldapportal.ldap.sync.SyncDriftStore.Row(seq, new com.ldapportal.dto.sync.SyncDriftItem(c,
                "uid=" + uid + ",dc=dst", c == com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN ? null : "uid=" + uid + ",dc=src",
                uid, List.of()));
    }

    private static com.ldapportal.ldap.sync.SyncContentVerifier.Recheck recheck(String uid,
            com.ldapportal.ldap.sync.SyncContentVerifier.RecheckState state) {
        return new com.ldapportal.ldap.sync.SyncContentVerifier.Recheck("uid=" + uid + ",dc=src", state, uid,
                "uid=" + uid + ",dc=dst",
                state == com.ldapportal.ldap.sync.SyncContentVerifier.RecheckState.MISMATCH
                        ? List.of(new com.ldapportal.dto.sync.SyncDriftItem.AttributeDiff("mail", List.of("a@x"),
                                List.of("b@x"), false, false))
                        : List.of(),
                state == com.ldapportal.ldap.sync.SyncContentVerifier.RecheckState.NOT_MEMBER
                        ? "The source entry no longer belongs to this set." : null);
    }

    /** The stored verify's time a drift action is made against. */
    private static final java.time.OffsetDateTime AT = java.time.OffsetDateTime.parse("2026-11-05T08:00:00Z");

    /** A set whose stored verify ran at {@link #AT}. */
    private static SyncSet verifiedSet() {
        SyncSet set = new SyncSet();
        set.setLastVerifiedAt(AT);
        return set;
    }

    /** Matches a store delete of the rows at exactly these positions. */
    private static org.mockito.ArgumentMatcher<java.util.Collection<com.ldapportal.ldap.sync.SyncDriftStore.Row>> rowsAt(
            Integer... seqs) {
        return rows -> rows.stream().map(com.ldapportal.ldap.sync.SyncDriftStore.Row::seq).toList().equals(List.of(seqs));
    }

    private UUID driftFixture() {
        UUID setId = UUID.randomUUID();
        SyncSet set = verifiedSet();
        set.setId(setId);
        set.setLinkId(UUID.randomUUID());
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        var M = com.ldapportal.entity.enums.SyncDriftCategory.MISSING;
        var D = com.ldapportal.entity.enums.SyncDriftCategory.MISMATCH;
        var O = com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN;
        when(driftStore.bySeq(eq(setId), any())).thenReturn(List.of(
                driftRow(0, M, "new"), driftRow(1, D, "drift"), driftRow(2, O, "old"),
                driftRow(3, D, "fixed"), driftRow(4, M, "gone")));
        var S = com.ldapportal.ldap.sync.SyncContentVerifier.RecheckState.class;
        when(verifier.recheck(eq(setId), any())).thenReturn(List.of(
                recheck("new", com.ldapportal.ldap.sync.SyncContentVerifier.RecheckState.MISSING),
                recheck("drift", com.ldapportal.ldap.sync.SyncContentVerifier.RecheckState.MISMATCH),
                recheck("fixed", com.ldapportal.ldap.sync.SyncContentVerifier.RecheckState.IN_SYNC),
                recheck("gone", com.ldapportal.ldap.sync.SyncContentVerifier.RecheckState.NOT_MEMBER)));
        return setId;
    }

    @Test
    void previewDriftRepair_saysWhatEachItemWouldGet_afterALiveRecheck() {
        UUID setId = driftFixture();

        var items = service.previewDriftRepair(setId, List.of(0, 1, 2, 3, 4), AT).items();

        assertThat(items).extracting(i -> i.seq() + ":" + i.action())
                .containsExactly("0:CREATE", "1:REAPPLY", "2:SKIP", "3:SKIP", "4:SKIP");
        assertThat(items.get(1).attributes()).singleElement().extracting(a -> a.attribute()).isEqualTo("mail");
        assertThat(items.get(2).reason()).contains("no source member");
        assertThat(items.get(3).reason()).isEqualTo("It no longer drifts.");
        assertThat(items.get(4).reason()).contains("no longer belongs");
        // Orphans aren't re-checked: they have no source member.
        verify(verifier).recheck(setId, List.of("uid=new,dc=src", "uid=drift,dc=src", "uid=fixed,dc=src",
                "uid=gone,dc=src"));
        verify(engine, never()).process(any(UUID.class), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void repairDrift_runsTheEngine_auditsEachWrite_andDropsWhatIsFixed() {
        UUID setId = driftFixture();
        when(linkRepo.findById(any())).thenReturn(Optional.empty());
        SyncLink link = new SyncLink();
        UUID targetDir = UUID.randomUUID();
        link.setTargetDirId(targetDir);
        when(linkRepo.findById(any())).thenReturn(Optional.of(link));
        when(engine.process(setId, "uid=new,dc=src", false)).thenReturn(new com.ldapportal.ldap.sync.RecomputeOutcome(
                com.ldapportal.dto.sync.SyncPreviewAction.ADD, "uid=new,dc=dst", null));
        when(engine.process(setId, "uid=drift,dc=src", false)).thenReturn(new com.ldapportal.ldap.sync.RecomputeOutcome(
                com.ldapportal.dto.sync.SyncPreviewAction.MODIFY, "uid=drift,dc=dst", null, List.of("mail")));
        var left = new java.util.EnumMap<com.ldapportal.entity.enums.SyncDriftCategory, Long>(
                com.ldapportal.entity.enums.SyncDriftCategory.class);
        left.put(com.ldapportal.entity.enums.SyncDriftCategory.MISSING, 1L);
        left.put(com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN, 1L);
        left.put(com.ldapportal.entity.enums.SyncDriftCategory.MISMATCH, 0L);
        when(driftStore.countsByCategory(setId)).thenReturn(left);
        var principal = new com.ldapportal.auth.AuthPrincipal(com.ldapportal.auth.PrincipalType.SUPERADMIN,
                UUID.randomUUID(), "ops");

        var r = service.repairDrift(setId, List.of(0, 1, 2, 3, 4), AT, principal);

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.reapplied()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(3);
        // Past the hash gate, each just before its own recompute, and only for the entries being repaired.
        var order = org.mockito.Mockito.inOrder(membershipRepo, engine);
        order.verify(membershipRepo).forgetAppliedContentOf(eq(setId), eq(List.of("new")), any());
        order.verify(engine).process(setId, "uid=new,dc=src", false);
        order.verify(membershipRepo).forgetAppliedContentOf(eq(setId), eq(List.of("drift")), any());
        order.verify(engine).process(setId, "uid=drift,dc=src", false);
        verify(membershipRepo, org.mockito.Mockito.times(2)).forgetAppliedContentOf(any(), any(), any());
        verify(engine, never()).process(eq(setId), eq("uid=gone,dc=src"), org.mockito.ArgumentMatchers.anyBoolean());
        // Each write audited with the operator; attribute names only.
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<java.util.Map<String, Object>> detail = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).record(eq(principal), eq(targetDir),
                eq(com.ldapportal.entity.enums.AuditAction.ENTRY_CREATE), eq("uid=new,dc=dst"), any());
        verify(auditService).record(eq(principal), eq(targetDir),
                eq(com.ldapportal.entity.enums.AuditAction.ENTRY_UPDATE), eq("uid=drift,dc=dst"), detail.capture());
        assertThat(detail.getValue()).containsEntry("operation", "drift-repair").containsEntry("attributes", List.of("mail"));
        // Created, re-applied, and found already fixed leave the stored verify; counts restated.
        verify(driftStore).delete(eq(setId), org.mockito.ArgumentMatchers.argThat(rowsAt(0, 1, 3)));
        // Only while the verify the items came from is still the stored one.
        verify(setRepo).restateVerifyCounts(setId, AT, 1, 1, 0);
    }

    @Test
    void repairDrift_reportsHeldAndFailedOutcomes_withoutAuditingThem() {
        UUID setId = driftFixture();
        when(linkRepo.findById(any())).thenReturn(Optional.empty());
        when(engine.process(setId, "uid=new,dc=src", false)).thenReturn(new com.ldapportal.ldap.sync.RecomputeOutcome(
                com.ldapportal.dto.sync.SyncPreviewAction.REVIEW, "uid=new,dc=dst", "placement conflict"));
        when(engine.process(setId, "uid=drift,dc=src", false)).thenThrow(new IllegalStateException("contended"));

        var r = service.repairDrift(setId, List.of(0, 1), AT, null);

        assertThat(r.held()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.items()).extracting(i -> i.outcome().name()).contains("HELD", "FAILED");
        verify(auditService, never()).record(any(), any(), any(), any(), any());
        verify(driftStore).delete(eq(setId), org.mockito.ArgumentMatchers.argThat(rowsAt(3))); // only the one found already in sync
    }

    @Test
    void repairDrift_cutShort_leavesTheHashOfEntriesItNeverReached() {
        UUID setId = driftFixture();
        when(linkRepo.findById(any())).thenReturn(Optional.empty());
        // The first recompute fails hard (e.g. the target went away mid-repair).
        when(engine.process(setId, "uid=new,dc=src", false)).thenThrow(new IllegalStateException("target down"));
        when(engine.process(setId, "uid=drift,dc=src", false)).thenThrow(new IllegalStateException("target down"));

        service.repairDrift(setId, List.of(0, 1), AT, null);

        // Each identity is reset only as its own recompute is attempted.
        verify(membershipRepo).forgetAppliedContentOf(eq(setId), eq(List.of("new")), any());
        verify(membershipRepo).forgetAppliedContentOf(eq(setId), eq(List.of("drift")), any());
        verify(membershipRepo, never()).forgetAppliedContentOf(eq(setId),
                org.mockito.ArgumentMatchers.argThat(c -> c.size() > 1), any());
    }

    @Test
    void repairDrift_refusesAnEmptyOrOversizedSelection() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(verifiedSet()));
        assertThatThrownBy(() -> service.repairDrift(setId, List.of(), AT, null))
                .isInstanceOf(IllegalArgumentException.class);
        List<Integer> many = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) many.add(i);
        assertThatThrownBy(() -> service.previewDriftRepair(setId, many, AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At most 200");
    }

    @Test
    void deleteOrphans_needsTheTypedConfirmation() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(verifiedSet()));
        assertThatThrownBy(() -> service.deleteOrphans(setId, List.of(1), AT, "yes", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Type DELETE");
        verify(engine, never()).orphanDelete(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void deleteOrphans_deletesThroughTheEngine_auditsIt_andLeavesNonOrphansAlone() {
        UUID setId = UUID.randomUUID();
        SyncSet set = verifiedSet();
        set.setId(setId);
        set.setLinkId(UUID.randomUUID());
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(linkRepo.findById(any())).thenReturn(Optional.empty());
        var O = com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN;
        when(driftStore.bySeq(eq(setId), any())).thenReturn(List.of(
                driftRow(0, O, "ghost"), driftRow(1, O, "gone"), driftRow(2, O, "kids"), driftRow(3, O, "kept"),
                driftRow(4, com.ldapportal.entity.enums.SyncDriftCategory.MISSING, "new")));
        UUID dir = UUID.randomUUID();
        when(engine.orphanDelete(setId, "uid=ghost,dc=dst", true)).thenReturn(new com.ldapportal.dto.sync.SyncDeleteApproval(
                com.ldapportal.dto.sync.SyncPreviewAction.DELETE, dir, "uid=ghost,dc=dst", null));
        when(engine.orphanDelete(setId, "uid=gone,dc=dst", true)).thenReturn(new com.ldapportal.dto.sync.SyncDeleteApproval(
                com.ldapportal.dto.sync.SyncPreviewAction.UNCHANGED, dir, "uid=gone,dc=dst",
                "Any wording: the flag, not the message, marks it gone.", true));
        when(engine.orphanDelete(setId, "uid=kids,dc=dst", true)).thenReturn(new com.ldapportal.dto.sync.SyncDeleteApproval(
                com.ldapportal.dto.sync.SyncPreviewAction.ERROR, dir, "uid=kids,dc=dst", "delete failed: NOT_ALLOWED_ON_NONLEAF"));
        when(engine.orphanDelete(setId, "uid=kept,dc=dst", true)).thenReturn(new com.ldapportal.dto.sync.SyncDeleteApproval(
                com.ldapportal.dto.sync.SyncPreviewAction.UNCHANGED, dir, "uid=kept,dc=dst", "This set now manages it."));
        var principal = new com.ldapportal.auth.AuthPrincipal(com.ldapportal.auth.PrincipalType.SUPERADMIN,
                UUID.randomUUID(), "ops");

        var r = service.deleteOrphans(setId, List.of(0, 1, 2, 3, 4), AT, "DELETE", principal);

        assertThat(r.deleted()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(3);
        assertThat(r.items().get(4).message()).contains("Only orphaned entries");
        verify(engine, never()).orphanDelete(eq(setId), eq("uid=new,dc=dst"), org.mockito.ArgumentMatchers.anyBoolean());
        verify(auditService).record(eq(principal), eq(dir), eq(com.ldapportal.entity.enums.AuditAction.ENTRY_DELETE),
                eq("uid=ghost,dc=dst"), org.mockito.ArgumentMatchers.argThat(
                        d -> "orphan-delete".equals(((java.util.Map<?, ?>) d).get("operation"))));
        verify(auditService, org.mockito.Mockito.times(1)).record(any(), any(), any(), any(), any());
        // The deleted one and the one already gone leave the stored verify.
        verify(driftStore).delete(eq(setId), org.mockito.ArgumentMatchers.argThat(rowsAt(0, 1)));
    }

    @Test
    void driftActions_refuseASelectionFromAnOlderVerify() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(verifiedSet()));
        var older = AT.minusMinutes(5);

        assertThatThrownBy(() -> service.deleteOrphans(setId, List.of(1), older, "DELETE", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("drift list changed");
        assertThatThrownBy(() -> service.previewOrphanDelete(setId, List.of(1), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("drift list changed");
        assertThatThrownBy(() -> service.repairDrift(setId, List.of(1), older, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("drift list changed");
        assertThatThrownBy(() -> service.previewDriftRepair(setId, List.of(1), older))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("drift list changed");
        // The same instant in another offset is the same verify.
        when(driftStore.bySeq(eq(setId), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.previewOrphanDelete(setId, List.of(1),
                AT.withOffsetSameInstant(java.time.ZoneOffset.ofHours(2))))
                .hasMessageContaining("no longer stored");
        verify(engine, never()).orphanDelete(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(verifier, never()).recheck(any(), any());
    }

    @Test
    void previewOrphanDelete_checksWithoutApplying() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(verifiedSet()));
        when(driftStore.bySeq(eq(setId), any())).thenReturn(List.of(
                driftRow(0, com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN, "ghost")));
        when(engine.orphanDelete(setId, "uid=ghost,dc=dst", false)).thenReturn(new com.ldapportal.dto.sync.SyncDeleteApproval(
                com.ldapportal.dto.sync.SyncPreviewAction.DELETE, null, "uid=ghost,dc=dst", null));

        var items = service.previewOrphanDelete(setId, List.of(0), AT).items();

        assertThat(items).singleElement().satisfies(i -> assertThat(i.action().name()).isEqualTo("DELETE"));
        verify(engine, never()).orphanDelete(setId, "uid=ghost,dc=dst", true);
    }

    @Test
    void verifyContents_unavailableSet_isNotRecordedAsIncomplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(
                0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), false, false, "Sync set is disabled."));

        service.verifyContents(setId);

        verify(setRepo, never()).recordVerifyIncomplete(any(), any());
    }

    @Test
    void reconcileNow_refreshesDriftSnapshot_whenScanComplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(reconciler.reconcile(setId, SyncReconcileTrigger.MANUAL, "alice")).thenReturn(42);
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(
                10, 9, 7, 2, 1, 0,
                List.of(), List.of(), List.of(),
                true, true, null)); // complete scan: missing=2, orphan=1, mismatch=0

        int enumerated = service.reconcileNow(setId, "alice");

        assertThat(enumerated).isEqualTo(42);
        // A manual reconcile must refresh the cached drift, else the dashboard stays stale.
        verify(setRepo).recordVerifyResult(eq(setId), any(), eq(2), eq(1), eq(0));
        // ...and record it on the run it follows.
        verify(reconcileHistory).recordDrift(setId, 2, 1, 0);
    }

    @Test
    void reconcileNow_skipsPersist_whenScanIncomplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(reconciler.reconcile(setId, SyncReconcileTrigger.MANUAL, "alice")).thenReturn(1);
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(
                0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(),
                false, true, null)); // partial: source enumeration failed

        service.reconcileNow(setId, "alice");

        // A partial scan must not overwrite a previously good snapshot with low counts.
        verify(setRepo, never()).recordVerifyResult(any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void reconcileNow_returnsCount_whenSnapshotRefreshThrows() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(reconciler.reconcile(setId, SyncReconcileTrigger.MANUAL, "alice")).thenReturn(5);
        when(verifier.verify(setId)).thenThrow(new RuntimeException("ldap read blew up"));

        // The reconcile already applied; a verify failure during the refresh must not
        // surface as a failed reconcile.
        int enumerated = service.reconcileNow(setId, "alice");

        assertThat(enumerated).isEqualTo(5);
        verify(setRepo, never()).recordVerifyResult(any(), any(), anyInt(), anyInt(), anyInt());
        // ...but it's noted, so the stored drift shows as possibly out of date.
        verify(setRepo).recordVerifyIncomplete(eq(setId), any());
    }

    @Test
    void createSet_onAMultiRootDirectory_requiresExplicitBases() {
        UUID linkId = UUID.randomUUID();
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        SyncLink link = new SyncLink();
        link.setSourceDirId(srcId);
        link.setTargetDirId(dstId);
        com.ldapportal.entity.DirectoryConnection multi = new com.ldapportal.entity.DirectoryConnection();
        multi.setBaseDn("dc=example,dc=com");
        multi.setAdditionalBaseDns(new java.util.ArrayList<>(List.of("o=partners")));
        com.ldapportal.entity.DirectoryConnection single = new com.ldapportal.entity.DirectoryConnection();
        single.setBaseDn("dc=dst,dc=com");
        when(linkRepo.existsById(linkId)).thenReturn(true);
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(directoryRepo.findById(srcId)).thenReturn(Optional.of(multi));
        org.mockito.Mockito.lenient().when(directoryRepo.findById(dstId)).thenReturn(Optional.of(single));

        SyncSetRequest outside = new SyncSetRequest(linkId, "people", "ou=people,dc=other", null, null,
                "ou=Users,dc=dst,dc=com", null, null, null, SyncDeletePolicy.DELETE, null, null, null, true, null);
        assertThatThrownBy(() -> service.createSet(outside))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'ou=people,dc=other' isn't under any of the source directory's base DNs");

        // Under either of the source's roots is fine.
        org.mockito.Mockito.lenient().when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SyncSetRequest secondRoot = new SyncSetRequest(linkId, "partners", "ou=people,o=partners", null, null,
                "ou=Users,dc=dst,dc=com", null, null, null, SyncDeletePolicy.DELETE, null, null, null, true, null);
        assertThat(service.createSet(secondRoot).objectScopeBaseDn()).isEqualTo("ou=people,o=partners");
    }

    /**
     * A link from a source with roots c=us and dc=elsewhere to a target with
     * roots c=us and o=partners.
     */
    private UUID linkToMultiRootTarget() {
        UUID linkId = UUID.randomUUID();
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        SyncLink link = new SyncLink();
        link.setSourceDirId(srcId);
        link.setTargetDirId(dstId);
        com.ldapportal.entity.DirectoryConnection source = new com.ldapportal.entity.DirectoryConnection();
        source.setBaseDn("c=us");
        source.setAdditionalBaseDns(new java.util.ArrayList<>(List.of("dc=elsewhere")));
        com.ldapportal.entity.DirectoryConnection multi = new com.ldapportal.entity.DirectoryConnection();
        multi.setBaseDn("c=us");
        multi.setAdditionalBaseDns(new java.util.ArrayList<>(List.of("o=partners")));
        when(linkRepo.existsById(linkId)).thenReturn(true);
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        org.mockito.Mockito.lenient().when(directoryRepo.findById(srcId)).thenReturn(Optional.of(source));
        when(directoryRepo.findById(dstId)).thenReturn(Optional.of(multi));
        org.mockito.Mockito.lenient().when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return linkId;
    }

    @Test
    void blankTargetBase_onAMultiRootTarget_isAccepted_whenTheSourceScopeIsUnderOneOfItsRoots() {
        UUID linkId = linkToMultiRootTarget();
        SyncSetRequest sameDns = new SyncSetRequest(linkId, "groups", "o=groups,c=us", null, null, null,
                null, null, null, SyncDeletePolicy.DELETE, null, null, null, true, null);

        assertThat(service.createSet(sameDns).targetBaseDn()).isNull(); // entries keep their source DNs
    }

    @Test
    void blankTargetBase_onAMultiRootTarget_isRefused_whenTheSourceScopeIsUnderNoneOfItsRoots() {
        UUID linkId = linkToMultiRootTarget();
        SyncSetRequest elsewhere = new SyncSetRequest(linkId, "groups", "o=groups,dc=elsewhere", null, null, null,
                null, null, null, SyncDeletePolicy.DELETE, null, null, null, true, null);

        assertThatThrownBy(() -> service.createSet(elsewhere))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'o=groups,dc=elsewhere' isn't under any of the target directory's base DNs");
    }

    @Test
    void recheckTarget_forgetsAppliedContent_andRequestsAReconcile() {
        UUID setId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setEnabled(true);
        set.setLinkId(linkId);
        SyncLink link = new SyncLink();
        link.setEnabled(true);
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(membershipRepo.forgetAppliedContent(eq(setId), any())).thenReturn(4741);

        SyncRecheckResult result = service.recheckTarget(setId, "alice");

        assertThat(result.entries()).isEqualTo(4741);
        assertThat(result.reconcileQueued()).isTrue();
        verify(setRepo).requestReconcileOfSet(setId);
    }

    @Test
    void recheckTarget_onADisabledSetOrLink_waitsForThemToBeEnabled() {
        UUID setId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setLinkId(linkId);
        set.setEnabled(false);
        SyncLink link = new SyncLink();
        link.setEnabled(true);
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        org.mockito.Mockito.lenient().when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));

        assertThat(service.recheckTarget(setId, "alice").reconcileQueued()).isFalse();

        set.setEnabled(true);
        link.setEnabled(false);
        assertThat(service.recheckTarget(setId, "alice").reconcileQueued()).isFalse();
    }

    private SyncSet enabledSet(UUID setId) {
        UUID linkId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setEnabled(true);
        set.setLinkId(linkId);
        SyncLink link = new SyncLink();
        link.setEnabled(true);
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        org.mockito.Mockito.lenient().when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        return set;
    }

    @Test
    void startReconcile_runsTheReconcileInTheBackground_asAManualRun() {
        UUID setId = UUID.randomUUID();
        enabledSet(setId);

        service.startReconcile(setId, "alice");

        verify(reconciler, org.mockito.Mockito.timeout(5000))
                .reconcile(setId, com.ldapportal.entity.enums.SyncReconcileTrigger.MANUAL, "alice");
    }

    @Test
    void startReconcile_whileOneIsRunning_isAConflict() {
        UUID setId = UUID.randomUUID();
        SyncSet set = enabledSet(setId);
        org.springframework.test.util.ReflectionTestUtils.setField(set, "reconcileLeaseToken", UUID.randomUUID());
        org.springframework.test.util.ReflectionTestUtils.setField(set, "reconcileLeaseHeartbeatAt",
                java.time.OffsetDateTime.now().minusSeconds(20));

        assertThatThrownBy(() -> service.startReconcile(setId, "alice"))
                .isInstanceOf(com.ldapportal.exception.ConflictException.class);
        verify(reconciler, never()).reconcile(any(), any(), any());
    }

    @Test
    void startReconcile_aLeaseLeftByAStoppedServer_doesNotBlockIt() {
        UUID setId = UUID.randomUUID();
        SyncSet set = enabledSet(setId);
        org.springframework.test.util.ReflectionTestUtils.setField(set, "reconcileLeaseToken", UUID.randomUUID());
        org.springframework.test.util.ReflectionTestUtils.setField(set, "reconcileLeaseHeartbeatAt",
                java.time.OffsetDateTime.now().minusHours(1));

        service.startReconcile(setId, "alice");

        verify(reconciler, org.mockito.Mockito.timeout(5000))
                .reconcile(setId, com.ldapportal.entity.enums.SyncReconcileTrigger.MANUAL, "alice");
    }

    @Test
    void startReconcile_onADisabledSet_isRefused() {
        UUID setId = UUID.randomUUID();
        SyncSet set = enabledSet(setId);
        set.setEnabled(false);

        assertThatThrownBy(() -> service.startReconcile(setId, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enable the sync set and its link");
        verify(reconciler, never()).reconcile(any(), any(), any());
    }

    @Test
    void requestReconcile_queuesOne_andSaysWhetherItWillRun() {
        UUID setId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setEnabled(true);
        set.setLinkId(linkId);
        SyncLink link = new SyncLink();
        link.setEnabled(true);
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));

        assertThat(service.requestReconcile(setId, "alice")).isTrue();
        verify(setRepo).requestReconcileOfSet(setId);
        verify(membershipRepo, never()).forgetAppliedContent(any(), any());

        link.setEnabled(false);
        assertThat(service.requestReconcile(setId, "alice")).isFalse();
    }

    @Test
    void requestReconcile_unknownSet_isNotFound() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestReconcile(setId, "alice"))
                .isInstanceOf(com.ldapportal.exception.ResourceNotFoundException.class);
        verify(setRepo, never()).requestReconcileOfSet(any());
    }

    @Test
    void recheckTarget_unknownSet_isNotFound() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recheckTarget(setId, "alice"))
                .isInstanceOf(com.ldapportal.exception.ResourceNotFoundException.class);
        verify(membershipRepo, never()).forgetAppliedContent(any(), any());
    }

    @Test
    void deleteSet_deletesThenAuditsAgainstTheTargetDirectory_withWhatWasDiscarded() {
        UUID setId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID targetDir = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(setId);
        set.setName("people");
        set.setLinkId(linkId);
        set.setTargetBaseDn("ou=Users,dc=dst");
        UUID sourceDir = UUID.randomUUID();
        SyncLink link = new SyncLink();
        link.setSourceDirId(sourceDir);
        link.setTargetDirId(targetDir);
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");
        stubDirectoryName(sourceDir, "HR LDAP");
        stubDirectoryName(targetDir, "Corp AD");
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(setRepo.findAll()).thenReturn(List.of(set));
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(membershipRepo.countBySyncSetId(setId)).thenReturn(876L);
        when(membershipRepo.countBySyncSetIdAndState(setId, com.ldapportal.entity.enums.MembershipState.REVIEW))
                .thenReturn(3L);
        when(reconcileRunRepo.countBySyncSetId(setId)).thenReturn(12L);

        service.deleteSet(setId, admin);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(setRepo, auditService);
        order.verify(setRepo).delete(set);
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("syncSetName", "people");
        detail.put("sourceDirectory", "HR LDAP");
        detail.put("targetDirectory", "Corp AD");
        detail.put("syncSetId", setId.toString());
        detail.put("linkId", linkId.toString());
        detail.put("managedEntries", 876L);
        detail.put("pendingReview", 3L);
        detail.put("reconcileRuns", 12L);
        order.verify(auditService).record(eq(admin), eq(targetDir), eq(AuditAction.SYNC_SET_DELETE),
                eq("ou=Users,dc=dst"), eq(detail));
    }

    @Test
    void deleteLink_deletesThenAuditsAgainstTheTargetDirectory_withItsChangelogPosition() {
        UUID linkId = UUID.randomUUID();
        UUID sourceDir = UUID.randomUUID();
        UUID targetDir = UUID.randomUUID();
        SyncLink link = new SyncLink();
        link.setDisplayName("hr->corp");
        link.setSourceDirId(sourceDir);
        link.setTargetDirId(targetDir);
        link.setCaptureMode(com.ldapportal.entity.enums.SyncCaptureMode.CHANGELOG);
        link.setChangelogFormat(com.ldapportal.entity.enums.ChangelogFormat.values()[0]);
        link.setChangelogBaseDn("cn=changelog");
        link.setChangelogLastChangeNumber(4242L);
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(setRepo.findAllByLinkId(linkId)).thenReturn(List.of());
        stubDirectoryName(sourceDir, "HR LDAP");
        stubDirectoryName(targetDir, "Corp AD");

        service.deleteLink(linkId, admin);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(linkRepo, auditService);
        order.verify(linkRepo).delete(link);
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("linkId", linkId.toString());
        detail.put("linkName", "hr->corp");
        detail.put("sourceDirectory", "HR LDAP");
        detail.put("targetDirectory", "Corp AD");
        detail.put("sourceDirId", sourceDir.toString());
        detail.put("targetDirId", targetDir.toString());
        detail.put("captureMode", "CHANGELOG");
        detail.put("changelogFormat", com.ldapportal.entity.enums.ChangelogFormat.values()[0].toString());
        detail.put("changelogBaseDn", "cn=changelog");
        detail.put("changelogLastChangeNumber", 4242L);
        order.verify(auditService).record(eq(admin), eq(targetDir), eq(AuditAction.SYNC_LINK_DELETE),
                isNull(), eq(detail));
    }

    @Test
    void deleteLink_withSetsStillOnIt_isRefused_andNotAudited() {
        UUID linkId = UUID.randomUUID();
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(new SyncLink()));
        when(setRepo.findAllByLinkId(linkId)).thenReturn(List.of(new SyncSet()));

        assertThatThrownBy(() -> service.deleteLink(linkId,
                new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync sets");
        verify(linkRepo, never()).delete(any(SyncLink.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteImpact_countsWhatWouldBeDiscarded_andNamesTheOtherSets() {
        UUID setId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(setId);
        set.setName("people");
        SyncSet groups = new SyncSet();
        groups.setId(UUID.randomUUID());
        groups.setName("groups");
        SyncSet admins = new SyncSet();
        admins.setId(UUID.randomUUID());
        admins.setName("Admins");
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(setRepo.findAll()).thenReturn(List.of(groups, set, admins));
        when(membershipRepo.countBySyncSetId(setId)).thenReturn(5L);
        when(membershipRepo.countBySyncSetIdAndState(setId, com.ldapportal.entity.enums.MembershipState.REVIEW))
                .thenReturn(0L);
        when(reconcileRunRepo.countBySyncSetId(setId)).thenReturn(2L);

        var impact = service.deleteImpact(setId);

        assertThat(impact.name()).isEqualTo("people");
        assertThat(impact.managed()).isEqualTo(5);
        assertThat(impact.pendingReview()).isZero();
        assertThat(impact.reconcileRuns()).isEqualTo(2);
        assertThat(impact.otherSets()).containsExactly("Admins", "groups");
    }

    @Test
    void approveDelete_auditsTheTargetDelete() {
        UUID setId = UUID.randomUUID();
        UUID targetDir = UUID.randomUUID();
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");
        UUID linkId = UUID.randomUUID();
        UUID sourceDir = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setName("people");
        set.setLinkId(linkId);
        SyncLink link = new SyncLink();
        link.setSourceDirId(sourceDir);
        link.setTargetDirId(targetDir);
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        stubDirectoryName(sourceDir, "HR LDAP");
        stubDirectoryName(targetDir, "Corp AD");
        when(engine.approveDelete(setId, "id-1")).thenReturn(new SyncDeleteApproval(
                SyncPreviewAction.DELETE, targetDir, "uid=a,dc=dst", null));

        SyncDeleteApproval result = service.approveDelete(setId, "id-1", admin);

        assertThat(result.outcome()).isEqualTo(SyncPreviewAction.DELETE);
        verify(auditService).record(eq(admin), eq(targetDir), eq(AuditAction.ENTRY_DELETE), eq("uid=a,dc=dst"),
                eq(java.util.Map.of("source", "directory-sync", "operation", "approved-review-delete",
                        "syncSetName", "people", "sourceDirectory", "HR LDAP", "targetDirectory", "Corp AD",
                        "syncSetId", setId.toString(), "identity", "id-1")));
    }

    @Test
    void approveDelete_doesNotAudit_whenNothingWasDeleted() {
        UUID setId = UUID.randomUUID();
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(engine.approveDelete(setId, "id-1")).thenReturn(new SyncDeleteApproval(
                SyncPreviewAction.ERROR, UUID.randomUUID(), "uid=a,dc=dst", "delete failed: 50"));

        service.approveDelete(setId, "id-1", admin);

        verify(auditService, never()).record(any(), any(), any(), any(), any());
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

    // ── link capture config ──────────────────────────────────────────────────

    private SyncLinkRequest linkReq(SyncCaptureMode mode, ChangelogFormat format, String baseDn) {
        return new SyncLinkRequest("link", SRC_DIR, DST_DIR, true, mode, format, baseDn);
    }

    @Test
    void createLink_rejectsChangelogFormatWithoutSyncCapture() {
        when(directoryRepo.existsById(any())).thenReturn(true);

        assertThatThrownBy(() -> service.createLink(
                linkReq(SyncCaptureMode.CHANGELOG, ChangelogFormat.OPENLDAP_ACCESSLOG, "cn=accesslog")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported for CHANGELOG capture");
        verify(linkRepo, never()).save(any());
    }

    @Test
    void updateLink_switchingFeed_resetsCursorAndHealth() {
        SyncLink existing = changelogLink(ChangelogFormat.DSEE_CHANGELOG, "cn=changelog");
        existing.setChangelogHealth(SyncChangelogHealth.STALLED);
        existing.setChangelogLastError("old feed error");
        existing.setChangelogLastErrorAt(java.time.OffsetDateTime.now());
        existing.setChangelogLastPolledAt(java.time.OffsetDateTime.now());
        when(directoryRepo.existsById(any())).thenReturn(true);
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // A DSEE changeNumber is meaningless as an OUD cookie.
        service.updateLink(existing.getId(),
                linkReq(SyncCaptureMode.CHANGELOG, ChangelogFormat.OUD_COOKIE_CHANGELOG, "cn=changelog"));

        assertThat(existing.getChangelogFormat()).isEqualTo(ChangelogFormat.OUD_COOKIE_CHANGELOG);
        assertThat(existing.getChangelogCursorToken()).isNull();
        assertThat(existing.getChangelogLastChangeNumber()).isNull();
        assertThat(existing.getChangelogSourceLastChangeNumber()).isNull();
        // The old feed's health/error no longer describe this link.
        assertThat(existing.getChangelogHealth()).isEqualTo(SyncChangelogHealth.HEALTHY);
        assertThat(existing.getChangelogLastError()).isNull();
        assertThat(existing.getChangelogLastErrorAt()).isNull();
        assertThat(existing.getChangelogLastPolledAt()).isNull();
    }

    @Test
    void updateLink_whitespaceOnlyBaseDnEdit_keepsCursor_andStoresTrimmed() {
        SyncLink existing = changelogLink(ChangelogFormat.OUD_COOKIE_CHANGELOG, "cn=changelog");
        when(directoryRepo.existsById(any())).thenReturn(true);
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateLink(existing.getId(),
                linkReq(SyncCaptureMode.CHANGELOG, ChangelogFormat.OUD_COOKIE_CHANGELOG, "  cn=changelog "));

        assertThat(existing.getChangelogBaseDn()).isEqualTo("cn=changelog");
        assertThat(existing.getChangelogCursorToken()).isEqualTo("42");
    }

    @Test
    void updateLink_sameFeed_keepsCursor() {
        SyncLink existing = changelogLink(ChangelogFormat.OUD_COOKIE_CHANGELOG, "cn=changelog");
        existing.setChangelogCursorToken("dc=example,dc=com:0000018c2f3a1b2c00010000000a;");
        when(directoryRepo.existsById(any())).thenReturn(true);
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateLink(existing.getId(),
                linkReq(SyncCaptureMode.CHANGELOG, ChangelogFormat.OUD_COOKIE_CHANGELOG, "cn=changelog"));

        assertThat(existing.getChangelogCursorToken())
                .isEqualTo("dc=example,dc=com:0000018c2f3a1b2c00010000000a;");
    }

    private static final UUID SRC_DIR = UUID.randomUUID();
    private static final UUID DST_DIR = UUID.randomUUID();

    private static SyncLink changelogLink(ChangelogFormat format, String baseDn) {
        SyncLink l = new SyncLink();
        l.setId(UUID.randomUUID());
        l.setSourceDirId(SRC_DIR);
        l.setTargetDirId(DST_DIR);
        l.setCaptureMode(SyncCaptureMode.CHANGELOG);
        l.setChangelogFormat(format);
        l.setChangelogBaseDn(baseDn);
        l.setChangelogCursorToken("42");
        l.setChangelogLastChangeNumber(42L);
        l.setChangelogSourceLastChangeNumber(50L);
        return l;
    }

    private void stubDirectoryName(UUID id, String name) {
        com.ldapportal.entity.DirectoryConnection dc = new com.ldapportal.entity.DirectoryConnection();
        dc.setDisplayName(name);
        when(directoryRepo.findById(id)).thenReturn(Optional.of(dc));
    }
}
