// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.audit;

import com.ldapportal.entity.enums.AuditAction;

import java.util.Map;
import java.util.UUID;

/**
 * SPI for plugging extra fields into the audit-event {@code detail}
 * JSONB column at record time.
 *
 * <p>Use case: an addon needs to tag every audit row produced for
 * directories it mediates, so compliance reports can filter on the
 * tag without a schema migration. The ISVA addon
 * ({@code com.ldapportal.addons.isva.IsvaAuditDetailContributor})
 * was the motivating example — it stamps
 * {@code vendorIntegration: "ISVA"} on every audit row whose
 * directory has an active ISVA config, plus {@code softDisable: true}
 * on USER_DELETE rows when the configured delete policy is
 * {@code DISABLE} rather than {@code HARD_DELETE}.</p>
 *
 * <p>Spring picks up implementations via component scan and
 * {@link com.ldapportal.service.AuditService} merges the maps from
 * every contributor into the caller-supplied {@code detail} before
 * persisting. Contributor output takes precedence over the
 * caller-supplied map on key collision, so addons can override
 * (rare). When no contributor is registered the audit record
 * shape is byte-identical to pre-SPI behaviour.</p>
 *
 * <p>Implementations should be cheap and side-effect-free. They run
 * synchronously on the {@code AuditService.record} path (which is
 * already {@code @Async}, so adding ~ms per write doesn't block the
 * user-facing op, but accumulating contributors all hitting the DB
 * still bloats the audit pipeline).</p>
 */
public interface AuditDetailContributor {

    /**
     * Return additional detail fields to merge into the audit row, or
     * an empty map when this contributor has nothing to add.
     *
     * @param directoryId the directory being modified (nullable for
     *                    system-level events)
     * @param action      the audit action being recorded
     * @param targetDn    the LDAP DN being touched (nullable when the
     *                    action isn't DN-scoped — e.g.
     *                    {@code BULK_ATTRIBUTE_UPDATE})
     * @param baseDetail  the caller-supplied detail map (may be null
     *                    or unmodifiable; contributors must not
     *                    mutate it — return a new map of their own
     *                    additions)
     * @return additional fields to fold in. Never null; empty map is
     *         the no-op signal. Keys collide with {@code baseDetail}
     *         only deliberately; document that in your contributor
     *         when it happens.
     */
    Map<String, Object> contribute(UUID directoryId,
                                   AuditAction action,
                                   String targetDn,
                                   Map<String, Object> baseDetail);
}
