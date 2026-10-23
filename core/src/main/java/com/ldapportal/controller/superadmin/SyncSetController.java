// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.RequiresSuperadminPermission;
import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.dto.PageResponse;
import com.ldapportal.dto.sync.MembershipResponse;
import com.ldapportal.dto.sync.RecomputeKeyRequest;
import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.dto.sync.SyncSetResponse;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SuperadminPermission;
import com.ldapportal.ldap.sync.SyncReconcileHistory;
import com.ldapportal.service.SyncConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for sync sets (selection + projection), the membership inventory, and
 * operator triggers (reconcile / recompute / dismiss a quarantine). Gated on the
 * {@code DIRECTORY_SYNC} entitlement and superadmin.
 */
@RestController
@RequestMapping("/api/v1/superadmin/sync/sets")
@Entitled(Entitlement.DIRECTORY_SYNC)
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiresSuperadminPermission(SuperadminPermission.VIEW_DIRECTORY_SYNC)
@RequiredArgsConstructor
public class SyncSetController {

    private final SyncConfigService service;
    private final com.ldapportal.ldap.sync.SyncSetDefaultsProvider defaultsProvider;

    @GetMapping
    public List<SyncSetResponse> list(@RequestParam(required = false) UUID linkId) {
        return service.listSets(linkId);
    }

    /**
     * Default excluded-attribute list (operational + password values). The editor
     * seeds a new sync set and its "reset to defaults" action from this so it
     * stays in lock-step with the engine.
     */
    @GetMapping("/excluded-attribute-defaults")
    public List<String> excludedAttributeDefaults() {
        return com.ldapportal.ldap.sync.SyncExcludedAttributes.DEFAULT_EXCLUDED;
    }

    /**
     * What the engine uses for each field left blank: the editor's info tips
     * list these, so they stay in lock-step with the engine and this server's
     * configuration.
     */
    @GetMapping("/defaults")
    public com.ldapportal.dto.sync.SyncSetDefaults defaults() {
        return defaultsProvider.defaults();
    }

    @PostMapping
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public ResponseEntity<SyncSetResponse> create(@Valid @RequestBody SyncSetRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createSet(req));
    }

    @GetMapping("/{id}")
    public SyncSetResponse get(@PathVariable UUID id) {
        return service.getSet(id);
    }

    @PutMapping("/{id}")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public SyncSetResponse update(@PathVariable UUID id, @Valid @RequestBody SyncSetRequest req) {
        return service.updateSet(id, req);
    }

    @DeleteMapping("/{id}")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal principal) {
        service.deleteSet(id, principal);
        return ResponseEntity.noContent().build();
    }

    /** What deleting the set would discard — shown in the confirmation before a delete. */
    @GetMapping("/{id}/delete-impact")
    public com.ldapportal.dto.sync.SyncSetDeleteImpact deleteImpact(@PathVariable UUID id) {
        return service.deleteImpact(id);
    }

    // ── Inventory + operator triggers ──────────────────────────────────────────

    @GetMapping("/{id}/memberships")
    public PageResponse<MembershipResponse> memberships(@PathVariable UUID id,
                                                        @RequestParam(required = false) MembershipState state,
                                                        @RequestParam(required = false) String q,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "50") int size) {
        int capped = Math.min(Math.max(size, 1), 200);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), capped, Sort.by("identity"));
        return PageResponse.of(service.listMemberships(id, state, q, pageable));
    }

    @PostMapping("/{id}/reconcile")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public Map<String, Integer> reconcile(@PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal principal) {
        return Map.of("enumerated", service.reconcileNow(id, principal != null ? principal.username() : null));
    }

    /**
     * The set's most recent reconcile runs, newest first: trigger, scan
     * completeness, and per-outcome counts (no attribute values).
     */
    /**
     * Everything recorded about one reconcile run: its counts and timing, the
     * instance that ran it, the set's settings at the time, failures grouped by
     * reason, sample target DNs per outcome (DNs only), and the drift found by
     * the verification after it.
     */
    @GetMapping("/{id}/reconcile-runs/{runId}")
    public com.ldapportal.dto.sync.SyncReconcileRunDetail reconcileRun(@PathVariable UUID id,
                                                                     @PathVariable UUID runId) {
        return service.reconcileRun(id, runId);
    }

    /**
     * Every entry the run changed or flagged, as CSV ({@code outcome,dn,reason})
     * in processing order — for a run whose set was recording all DNs (400
     * otherwise). A {@code FAULT} row carries the source DN or identity whose
     * recompute threw, since it never reached a target DN.
     */
    @GetMapping(value = "/{id}/reconcile-runs/{runId}/entries.csv", produces = "text/csv")
    public ResponseEntity<byte[]> reconcileRunEntries(@PathVariable UUID id, @PathVariable UUID runId) {
        // Built in memory like the other CSV exports, rather than streamed: a
        // StreamingResponseBody completes on an async dispatch, which the
        // security filter chain doesn't authorize. Rows are read in pages.
        service.requireRunEntries(id, runId);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8);
        service.writeRunEntriesCsv(id, runId, writer);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sync-run-" + runId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes.toByteArray());
    }

    /**
     * The set's most recent runs, newest first. {@code kind} narrows them to
     * {@code RECONCILE} runs or {@code INCREMENTAL} passes; both when absent.
     */
    @GetMapping("/{id}/reconcile-runs")
    public List<com.ldapportal.dto.sync.SyncReconcileRunResponse> reconcileRuns(
            @PathVariable UUID id, @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) SyncReconcileHistory.RunKind kind) {
        return service.reconcileRuns(id, Math.min(Math.max(limit, 1), 20), kind);
    }

    /**
     * Re-check the target (e.g. after restoring it from a backup): the next
     * reconcile re-reads every applied entry instead of trusting what the set
     * last wrote, and re-applies the ones that differ. Queues that reconcile.
     */
    @PostMapping("/{id}/recheck-target")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public com.ldapportal.dto.sync.SyncRecheckResult recheckTarget(
            @PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal principal) {
        return service.recheckTarget(id, principal != null ? principal.username() : null);
    }

    /** Dry-run preview of a reconcile (counts + sample deletions, no writes). */
    @GetMapping("/{id}/preview")
    public com.ldapportal.dto.sync.SyncReconcilePreview preview(@PathVariable UUID id) {
        return service.previewReconcile(id);
    }

    /**
     * The latest per-entry dry-run preview of the set: progress while it runs,
     * its summary once complete. Manage-only, like the entries: a preview shows
     * attribute values.
     */
    @GetMapping("/{id}/preview/run")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public com.ldapportal.dto.sync.SyncPreviewStatus previewStatus(@PathVariable UUID id) {
        return service.previewStatus(id);
    }

    /**
     * Start a per-entry dry-run preview in the background: plans the whole set
     * and predicts what a reconcile would do to each entry. No directory writes;
     * works for a disabled set. Joins the run already in progress, if any.
     */
    @PostMapping("/{id}/preview/run")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public ResponseEntity<com.ldapportal.dto.sync.SyncPreviewStatus> startPreview(
            @PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.accepted()
                .body(service.startPreview(id, principal != null ? principal.username() : null));
    }

    /**
     * One page of the set's completed preview (add / modify with attribute
     * changes / move / delete / review), ordered by target DN.
     * {@code changesOnly} leaves out entries that wouldn't change.
     */
    @GetMapping("/{id}/preview/entries")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public com.ldapportal.dto.sync.SyncPreviewPage previewEntries(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "true") boolean changesOnly) {
        return service.previewEntries(id, page, size, changesOnly);
    }

    /**
     * Independent content verification: re-reads the live source and target and
     * flags missing / orphaned / drifted entries (belts-and-suspenders, no writes).
     */
    @GetMapping("/{id}/verify")
    public com.ldapportal.dto.sync.SyncVerifyResult verify(@PathVariable UUID id) {
        return service.verifyContents(id);
    }

    @PostMapping("/{id}/recompute")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public ResponseEntity<Void> recompute(@PathVariable UUID id, @Valid @RequestBody RecomputeKeyRequest req) {
        service.recompute(id, req.key());
        return ResponseEntity.accepted().build();
    }

    /**
     * Approve a delete held for review: deletes the identity's target entry now,
     * whatever the delete policy or guard (the source is re-read first; an entry
     * back in scope is refused with 400).
     */
    @PostMapping("/{id}/memberships/{identity}/approve-delete")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public com.ldapportal.dto.sync.SyncDeleteApproval approveDelete(
            @PathVariable UUID id, @PathVariable String identity,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.approveDelete(id, identity, principal);
    }

    @DeleteMapping("/{id}/memberships/{identity}")
    @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_SYNC)
    public ResponseEntity<Void> dismiss(@PathVariable UUID id, @PathVariable String identity) {
        service.dismissMembership(id, identity);
        return ResponseEntity.noContent().build();
    }
}
