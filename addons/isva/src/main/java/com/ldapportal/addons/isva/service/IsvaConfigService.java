// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.dto.IsvaConfigDto;
import com.ldapportal.addons.isva.dto.ProbeResult;
import com.ldapportal.addons.isva.dto.UpsertIsvaConfigRequest;
import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-directory ISVA full-mode configuration logic, shared by the two
 * controllers that expose it: {@link com.ldapportal.addons.isva.controller.IsvaConfigController}
 * (addressed by the directory's surrogate id) and
 * {@code IsvaConfigBySlugController} (addressed by the directory's stable
 * IaC slug). The upsert is keyed by {@code directory_connection_id} — the
 * config row's primary key — so re-applying the same request converges to
 * one row per directory, which is exactly the idempotency IaC needs.
 *
 * <p>No credentials live in this config (it's directory-wide IVIA policy:
 * base DNs, topology mode, flags), so there are no write-only secret
 * fields to special-case.</p>
 */
@Service
@RequiredArgsConstructor
public class IsvaConfigService {

    private final VendorIntegrationIsvaConfigRepository configRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final IsvaConfigProbeService probeService;

    @Transactional(readOnly = true)
    public IsvaConfigDto get(UUID directoryId) {
        assertDirectoryExists(directoryId);
        return configRepo.findById(directoryId)
                .map(IsvaConfigDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ISVA configuration exists for directory " + directoryId
                                + ". PUT a config to create one."));
    }

    @Transactional
    public IsvaConfigDto upsert(UUID directoryId, UpsertIsvaConfigRequest req, AuthPrincipal principal) {
        return upsert(directoryId, req, principal, null);
    }

    /**
     * ISVA config upsert with an optional {@code If-Match} precondition (§4.4).
     * {@code expectedVersion}, when non-null, must equal the existing config's
     * current version or the write is rejected with a 412. The check applies
     * only when a config already exists — the first apply creates it and has no
     * prior version to match.
     */
    @Transactional
    public IsvaConfigDto upsert(UUID directoryId, UpsertIsvaConfigRequest req,
                                AuthPrincipal principal, Long expectedVersion) {
        assertDirectoryExists(directoryId);
        validateLinkedModeFields(req);

        VendorIntegrationIsvaConfig existing = configRepo.findById(directoryId).orElse(null);
        if (existing != null) {
            com.ldapportal.web.ETagSupport.requireMatch(expectedVersion, existing.getVersion());
        }
        VendorIntegrationIsvaConfig entity =
                existing != null ? existing : new VendorIntegrationIsvaConfig();
        entity.setDirectoryConnectionId(directoryId);
        entity.setEnabled(req.enabled());
        entity.setTopologyMode(req.topologyMode());
        entity.setSecAuthority(blankToNull(req.secAuthority()));
        entity.setDefaultValidUntilYears(req.defaultValidUntilYears());
        entity.setDeletePolicy(req.deletePolicy());
        entity.setRequireSecGroup(req.requireSecGroup());
        // Applies to both modes — normalize so secUser is always present
        // and the list is trimmed / de-duplicated.
        entity.setSecuserObjectClasses(normalizeObjectClasses(req.secuserObjectClasses()));

        // Linked-mode fields — set when LINKED, null when INLINE so
        // a topology-mode flip doesn't leave stale linked config
        // around to confuse the probe / interceptor.
        if (req.topologyMode() == IsvaTopologyMode.LINKED) {
            entity.setManagementDitBaseDn(req.managementDitBaseDn().trim());
            entity.setSecuserRdnAttribute(blankOrDefault(req.secuserRdnAttribute(), "secUUID"));
            entity.setSecuserRdnValueSource(req.secuserRdnValueSource() != null
                    ? req.secuserRdnValueSource() : IsvaRdnValueSource.GENERATED_UUID);
            entity.setGroupMemberTarget(req.groupMemberTarget() != null
                    ? req.groupMemberTarget() : entity.getGroupMemberTarget());
            entity.setOnDemographicDelete(req.onDemographicDelete() != null
                    ? req.onDemographicDelete() : entity.getOnDemographicDelete());
        } else {
            entity.setManagementDitBaseDn(null);
            // Leave secuserRdnAttribute / secuserRdnValueSource /
            // groupMemberTarget / onDemographicDelete at their stored
            // defaults — they're ignored in INLINE mode anyway and
            // clearing them would be unnecessary churn against the
            // audit columns. (secuserObjectClasses is set above; it
            // applies to inline mode too.)
        }

        entity.setUpdatedBy(principal != null ? principal.username() : "system");
        // saveAndFlush so the @Version increment lands before the response (and
        // its ETag) is built — a plain save would return the pre-update version.
        return IsvaConfigDto.from(configRepo.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public ProbeResult probe(UUID directoryId) {
        DirectoryConnection dir = directoryRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Directory not found"));
        VendorIntegrationIsvaConfig cfg = configRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ISVA configuration exists for directory " + directoryId
                                + " — save a config before probing."));
        return probeService.probe(dir, cfg);
    }

    /**
     * A single ISVA config rendered for export: the directory's stable IaC
     * slug plus the config as an {@link UpsertIsvaConfigRequest} — exactly the
     * {@code { directorySlug, config }} shape the bootstrap reconciler's
     * {@code isva} section consumes, so it round-trips cleanly. No secrets live
     * in this config, so nothing is redacted.
     */
    public record IsvaConfigExport(String directorySlug, UpsertIsvaConfigRequest config) {
    }

    /**
     * Export every stored ISVA config, keyed by its directory's slug and
     * ordered by slug for stable, diff-friendly output. Configs whose
     * directory is missing or has no slug are skipped — a slug-less directory
     * can't be re-targeted by the reconciler.
     */
    @Transactional(readOnly = true)
    public List<IsvaConfigExport> exportAll() {
        List<IsvaConfigExport> out = new ArrayList<>();
        for (VendorIntegrationIsvaConfig cfg : configRepo.findAll()) {
            DirectoryConnection dir =
                    directoryRepo.findById(cfg.getDirectoryConnectionId()).orElse(null);
            if (dir == null || dir.getSlug() == null || dir.getSlug().isBlank()) {
                continue;
            }
            UpsertIsvaConfigRequest req = new UpsertIsvaConfigRequest(
                    cfg.isEnabled(),
                    cfg.getTopologyMode(),
                    cfg.getSecAuthority(),
                    cfg.getDefaultValidUntilYears(),
                    cfg.getDeletePolicy(),
                    cfg.isRequireSecGroup(),
                    cfg.getSecuserObjectClasses(),
                    cfg.getManagementDitBaseDn(),
                    cfg.getSecuserRdnAttribute(),
                    cfg.getSecuserRdnValueSource(),
                    cfg.getGroupMemberTarget(),
                    cfg.getOnDemographicDelete());
            out.add(new IsvaConfigExport(dir.getSlug(), req));
        }
        out.sort(java.util.Comparator.comparing(IsvaConfigExport::directorySlug));
        return out;
    }

    /**
     * Resolve a directory's stable IaC slug to its surrogate id so the
     * slug-addressed endpoints can reuse the id-keyed logic above. 404s on
     * an unknown slug — automation targeting a directory that doesn't exist
     * should fail loudly, not silently create one.
     */
    @Transactional(readOnly = true)
    public UUID resolveDirectoryIdBySlug(String slug) {
        return directoryRepo.findBySlug(slug)
                .map(DirectoryConnection::getId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No directory exists with slug [" + slug + "]"));
    }

    // ── helpers ─────────────────────────────────────────────────────

    private void assertDirectoryExists(UUID directoryId) {
        if (!directoryRepo.existsById(directoryId)) {
            throw new ResourceNotFoundException("Directory not found");
        }
    }

    /**
     * Defence in depth — DB has a matching CHECK constraint, but
     * surfacing the validation here gives the operator a friendly error
     * instead of a 500-with-stack-trace.
     *
     * <p>{@link IllegalArgumentException} (not {@code ResponseStatusException})
     * because the core {@code GlobalExceptionHandler} maps the former to
     * a 400 ProblemDetail with the message as {@code detail}; the latter
     * falls through to the catch-all and surfaces as 500.</p>
     */
    private static void validateLinkedModeFields(UpsertIsvaConfigRequest req) {
        if (req.topologyMode() == IsvaTopologyMode.LINKED
                && (req.managementDitBaseDn() == null
                    || req.managementDitBaseDn().isBlank())) {
            throw new IllegalArgumentException(
                    "managementDitBaseDn is required when topologyMode is LINKED");
        }
    }

    /**
     * Normalize the configured secUser objectClass list: trim, drop
     * blanks, de-duplicate case-insensitively (first spelling wins), and
     * guarantee {@code secUser} is present — the lookup / probe filters
     * key on {@code (objectClass=secUser)}, so it can never be dropped.
     * A null / empty request resolves to just {@code [secUser]}.
     */
    private static List<String> normalizeObjectClasses(List<String> requested) {
        // LinkedHashMap keyed on lower-case name preserves insertion order
        // while collapsing case-variant duplicates.
        Map<String, String> seen = new LinkedHashMap<>();
        if (requested != null) {
            for (String oc : requested) {
                if (oc == null) {
                    continue;
                }
                String trimmed = oc.trim();
                if (!trimmed.isEmpty()) {
                    seen.putIfAbsent(trimmed.toLowerCase(), trimmed);
                }
            }
        }
        seen.putIfAbsent("secuser", "secUser");
        return new ArrayList<>(seen.values());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String blankOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
