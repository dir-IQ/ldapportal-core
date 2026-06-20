// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.core.entitlement.EditionScoped;
import com.ldapportal.core.entitlement.Entitlement;

/**
 * Output format a scheduled report is rendered into. Persisted as the enum name
 * in {@code scheduled_report_jobs.output_format}.
 *
 * <p>This is an {@link EditionScoped} catalogue: {@link #CSV} is part of the
 * core, edition-agnostic surface; {@link #PDF} requires {@link Entitlement#GOVERNANCE}
 * (the PDF {@code ReportRenderer} ships in {@code ee/governance}, so PDF is also
 * structurally absent in community by capability). Routing exposure through
 * {@link com.ldapportal.core.entitlement.EntitlementService#exposed(Class)} keeps
 * the gate in one place and lets the edition-leak guards cover this enum
 * automatically.</p>
 */
public enum ReportOutputFormat implements EditionScoped {

    CSV(null),
    PDF(Entitlement.GOVERNANCE);

    private final Entitlement requiredEntitlement;

    ReportOutputFormat(Entitlement requiredEntitlement) {
        this.requiredEntitlement = requiredEntitlement;
    }

    /** {@inheritDoc} {@code null} for the core surface ({@link #CSV}). */
    @Override
    public Entitlement requiredEntitlement() {
        return requiredEntitlement;
    }
}
