// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.ldap.replication.ReplicationLinkSnapshot;
import com.ldapportal.ldap.replication.reconcile.ReconciliationDiffer.DiffResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two-pass checksum reconciliation against a faked paged reader. Pins the
 * classification parity with {@link ReconciliationDiffer} and the
 * hydrate-only-on-difference contract (R-PP1).
 */
@ExtendWith(MockitoExtension.class)
class ChecksumReconcilerTest {

    @Mock private ReconciliationReadOps readOps;
    private ChecksumReconciler reconciler;

    private DirectoryConnection sourceDir;
    private DirectoryConnection targetDir;
    private ReplicationLinkSnapshot link;

    @BeforeEach
    void setup() {
        reconciler = new ChecksumReconciler(readOps);
        ReflectionTestUtils.setField(reconciler, "pageSize", 500);
        sourceDir = new DirectoryConnection(); sourceDir.setId(UUID.randomUUID()); sourceDir.setBaseDn("dc=x");
        targetDir = new DirectoryConnection(); targetDir.setId(UUID.randomUUID()); targetDir.setBaseDn("dc=x");
        link = new ReplicationLinkSnapshot(UUID.randomUUID(), "L",
                sourceDir, targetDir, null, null, true, false, List.of());
    }

    private ReconEntry e(String dn, String cn) {
        return new ReconEntry(dn, Map.of("cn", List.of(cn)));
    }

    /** Stub streamSubtree(dir, ...) to feed the given entries to the consumer. */
    @SuppressWarnings("unchecked")
    private void stubStream(DirectoryConnection dir, List<ReconEntry> entries) {
        doAnswer(inv -> {
            Consumer<ReconEntry> c = inv.getArgument(3);
            entries.forEach(c);
            return null;
        }).when(readOps).streamSubtree(eq(dir), anyString(), anyInt(), any());
    }

    private void stubReadEntry(DirectoryConnection dir, ReconEntry entry) {
        lenient().when(readOps.readEntry(eq(dir), eq(entry.dn()))).thenReturn(Optional.of(entry));
    }

    private DiffResult run(ReconcileDeleteAction del, Set<String> undelivered) {
        return reconciler.reconcile(link, "dc=x", "dc=x", undelivered, del);
    }

    @Test
    void identicalSubtrees_noFindings_andNoHydration() {
        stubStream(sourceDir, List.of(e("uid=a,dc=x", "Ann")));
        stubStream(targetDir, List.of(e("uid=a,dc=x", "Ann")));

        DiffResult r = run(ReconcileDeleteAction.REVIEW, Set.of());

        assertThat(r.findings()).isEmpty();
        assertThat(r.sourceCount()).isEqualTo(1);
        assertThat(r.targetCount()).isEqualTo(1);
        verify(readOps, never()).readEntry(any(), anyString());   // digests sufficed
    }

    @Test
    void missingEntry_classifiedAndHydratedFromSource() {
        var src = e("uid=b,dc=x", "Bob");
        stubStream(sourceDir, List.of(src));
        stubStream(targetDir, List.of());
        stubReadEntry(sourceDir, src);

        DiffResult r = run(ReconcileDeleteAction.REVIEW, Set.of());

        assertThat(r.missingCount()).isEqualTo(1);
        assertThat(r.findings().get(0).type()).isEqualTo(ReconciliationFindingType.MISSING_IN_TARGET);
        verify(readOps).readEntry(sourceDir, "uid=b,dc=x");       // hydrated only the discrepancy
    }

    @Test
    void drift_classifiedAndHydratedFromBothSides() {
        var src = e("uid=a,dc=x", "New");
        var tgt = e("uid=a,dc=x", "Old");
        stubStream(sourceDir, List.of(src));
        stubStream(targetDir, List.of(tgt));
        stubReadEntry(sourceDir, src);
        stubReadEntry(targetDir, tgt);

        DiffResult r = run(ReconcileDeleteAction.REVIEW, Set.of());

        assertThat(r.driftCount()).isEqualTo(1);
        assertThat(r.findings().get(0).type()).isEqualTo(ReconciliationFindingType.ATTRIBUTE_DRIFT);
        verify(readOps).readEntry(sourceDir, "uid=a,dc=x");
        verify(readOps).readEntry(targetDir, "uid=a,dc=x");
    }

    @Test
    void extraInTarget_gatedAndHydratedForUi() {
        var extra = e("uid=z,dc=x", "Zed");
        stubStream(sourceDir, List.of(e("uid=a,dc=x", "Ann")));
        stubStream(targetDir, List.of(e("uid=a,dc=x", "Ann"), extra));
        stubReadEntry(targetDir, extra);

        assertThat(run(ReconcileDeleteAction.IGNORE, Set.of()).findings()).isEmpty();

        DiffResult r = run(ReconcileDeleteAction.AUTO, Set.of());
        assertThat(r.extraCount()).isEqualTo(1);
        assertThat(r.findings().get(0).type()).isEqualTo(ReconciliationFindingType.EXTRA_IN_TARGET);
        assertThat(r.findings().get(0).targetDn()).isEqualTo("uid=z,dc=x");
    }

    @Test
    void shadowedFinding_isSuppressedAndNotHydrated() {
        var src = e("uid=b,dc=x", "Bob");
        stubStream(sourceDir, List.of(src));
        stubStream(targetDir, List.of());

        Set<String> undelivered = Set.of(ReconciliationDiffer.normDn("uid=b,dc=x"));
        DiffResult r = run(ReconcileDeleteAction.REVIEW, undelivered);

        assertThat(r.findings()).isEmpty();
        assertThat(r.suppressedCount()).isEqualTo(1);
        verify(readOps, never()).readEntry(any(), anyString());   // suppressed before hydration
    }

    /** Parity: a small dataset yields the same finding types as the pure differ. */
    @Test
    void parityWithPureDiffer_onMixedDataset() {
        List<ReconEntry> source = new ArrayList<>(List.of(
                e("uid=a,dc=x", "Ann"),     // identical
                e("uid=b,dc=x", "Bob"),     // missing in target
                e("uid=c,dc=x", "New")));   // drift
        List<ReconEntry> target = new ArrayList<>(List.of(
                e("uid=a,dc=x", "Ann"),
                e("uid=c,dc=x", "Old"),
                e("uid=z,dc=x", "Zed")));   // extra
        stubStream(sourceDir, source);
        stubStream(targetDir, target);
        source.forEach(s -> stubReadEntry(sourceDir, s));
        target.forEach(t -> stubReadEntry(targetDir, t));

        DiffResult checksum = run(ReconcileDeleteAction.REVIEW, Set.of());
        DiffResult pure = ReconciliationDiffer.diff(link, "dc=x", source, target, Set.of(), ReconcileDeleteAction.REVIEW);

        assertThat(checksum.missingCount()).isEqualTo(pure.missingCount()).isEqualTo(1);
        assertThat(checksum.driftCount()).isEqualTo(pure.driftCount()).isEqualTo(1);
        assertThat(checksum.extraCount()).isEqualTo(pure.extraCount()).isEqualTo(1);
    }
}
