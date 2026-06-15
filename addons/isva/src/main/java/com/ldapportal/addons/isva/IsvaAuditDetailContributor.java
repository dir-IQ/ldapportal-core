// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaDeletePolicy;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.core.audit.AuditDetailContributor;
import com.ldapportal.entity.enums.AuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tags audit rows produced for ISVA-mediated directories so that
 * compliance reports can filter on the integration without any
 * schema change.
 *
 * <ul>
 *   <li>{@code vendorIntegration: "ISVA"} on every audit row whose
 *       directory has an active (i.e. {@code enabled=true}) ISVA
 *       config row.</li>
 *   <li>{@code softDisable: true} additionally on {@link AuditAction#USER_DELETE}
 *       rows when the directory's configured {@link IsvaDeletePolicy}
 *       is {@code DISABLE} — these are the rows where the user
 *       "deletion" actually became a soft-disable
 *       ({@code secAcctValid=FALSE} + {@code secValidUntil=now}) on
 *       the secUser entry rather than a real LDAP DEL. Auditors
 *       need to distinguish the two cases when reading the log.</li>
 * </ul>
 *
 * <p>Returns an empty map when ISVA is not configured for the
 * directory — keeping the audit-row shape byte-identical to
 * baseline for non-ISVA directories.</p>
 *
 * <p>Cost: one PK lookup per audit-write on an ISVA directory.
 * {@code AuditService.record} is already {@code @Async}, so this
 * doesn't add latency to the user-facing op. If multiple addons
 * eventually all do similar lookups, a per-request cache on the
 * config row is the right next step — premature here.</p>
 */
@Component
@RequiredArgsConstructor
public class IsvaAuditDetailContributor implements AuditDetailContributor {

    private final VendorIntegrationIsvaConfigRepository configRepo;

    @Override
    public Map<String, Object> contribute(UUID directoryId,
                                          AuditAction action,
                                          String targetDn,
                                          Map<String, Object> baseDetail) {
        if (directoryId == null) return Map.of();

        Optional<VendorIntegrationIsvaConfig> maybe = configRepo.findById(directoryId);
        if (maybe.isEmpty() || !maybe.get().isEnabled()) return Map.of();

        VendorIntegrationIsvaConfig cfg = maybe.get();

        // Forward the originating profileId when the caller put it in
        // baseDetail (LdapOperationService.createUser does this when
        // the create was profile-driven). The map.put dance lets us
        // include the field conditionally without growing the SPI.
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("vendorIntegration", "ISVA");
        // USER_DELETE + DISABLE policy = soft-disable. HARD_DELETE
        // means a real LDAP DEL went out and the row is a regular
        // user-delete from the auditor's perspective; don't mark it.
        if (action == AuditAction.USER_DELETE && cfg.getDeletePolicy() == IsvaDeletePolicy.DISABLE) {
            out.put("softDisable", true);
        }
        if (baseDetail != null && baseDetail.get("profileId") != null) {
            out.put("profileId", baseDetail.get("profileId"));
        }
        return java.util.Map.copyOf(out);
    }
}
