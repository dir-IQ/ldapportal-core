// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.core.entitlement.EditionScoped;
import com.ldapportal.core.entitlement.Entitlement;

/**
 * A schedulable report-type descriptor contributed by a
 * {@link ScheduledReportContentProvider}.
 *
 * <p>Core cannot enumerate ee's compliance report types, so the schedulable
 * report-type catalogue is the <em>union of all providers' descriptors</em>
 * rather than a single core enum. Each descriptor carries its own edition
 * gating via {@link #requiredEntitlement()} — {@code null} for operational
 * (community) types, {@link Entitlement#GOVERNANCE} for compliance types — which
 * makes this descriptor itself the source of truth for the report-type axis of
 * the exposure gate (mis-gating a descriptor is caught by the descriptor-coverage
 * test, not the enum-based edition-leak guard).</p>
 *
 * @param id     stable identifier, sent by the client as the report type and
 *               persisted in {@code scheduled_report_jobs.report_type}; must equal
 *               the historical type-constant name verbatim so existing rows keep
 *               resolving. Must not collide across providers.
 * @param label  human-readable name for the schedule-form dropdown and as the
 *               rendered report title (see {@link RenderContext}).
 * @param requiredEntitlement the entitlement that must be present for this type
 *               to be exposed, or {@code null} when it is part of the core,
 *               edition-agnostic surface.
 */
public record ScheduledReportType(String id, String label, Entitlement requiredEntitlement)
        implements EditionScoped {

    public ScheduledReportType {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ScheduledReportType id must not be blank");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("ScheduledReportType label must not be blank");
        }
    }

    /** Convenience factory for a core, edition-agnostic (always-exposed) type. */
    public static ScheduledReportType core(String id, String label) {
        return new ScheduledReportType(id, label, null);
    }
}
