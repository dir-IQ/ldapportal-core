// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.dto.PageResponse;
import com.ldapportal.dto.sync.MembershipResponse;
import com.ldapportal.dto.sync.RecomputeKeyRequest;
import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.dto.sync.SyncSetResponse;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.service.SyncConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
@RequiredArgsConstructor
public class SyncSetController {

    private final SyncConfigService service;

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

    @PostMapping
    public ResponseEntity<SyncSetResponse> create(@Valid @RequestBody SyncSetRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createSet(req));
    }

    @GetMapping("/{id}")
    public SyncSetResponse get(@PathVariable UUID id) {
        return service.getSet(id);
    }

    @PutMapping("/{id}")
    public SyncSetResponse update(@PathVariable UUID id, @Valid @RequestBody SyncSetRequest req) {
        return service.updateSet(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deleteSet(id);
        return ResponseEntity.noContent().build();
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
    public Map<String, Integer> reconcile(@PathVariable UUID id) {
        return Map.of("enumerated", service.reconcileNow(id));
    }

    /** Dry-run preview of a reconcile (counts + sample deletions, no writes). */
    @GetMapping("/{id}/preview")
    public com.ldapportal.dto.sync.SyncReconcilePreview preview(@PathVariable UUID id) {
        return service.previewReconcile(id);
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
    public ResponseEntity<Void> recompute(@PathVariable UUID id, @Valid @RequestBody RecomputeKeyRequest req) {
        service.recompute(id, req.key());
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{id}/memberships/{identity}")
    public ResponseEntity<Void> dismiss(@PathVariable UUID id, @PathVariable String identity) {
        service.dismissMembership(id, identity);
        return ResponseEntity.noContent().build();
    }
}
