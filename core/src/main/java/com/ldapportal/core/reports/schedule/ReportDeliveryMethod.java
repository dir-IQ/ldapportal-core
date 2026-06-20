// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.core.entitlement.EditionScoped;
import com.ldapportal.core.entitlement.Entitlement;

/**
 * Where a scheduled report's rendered output is delivered. Persisted as the enum
 * name in {@code scheduled_report_jobs.delivery_method}.
 *
 * <p>This is an {@link EditionScoped} catalogue: {@link #EMAIL} is part of the
 * core, edition-agnostic surface; {@link #S3} requires
 * {@link Entitlement#GOVERNANCE}. Unlike the report-type and PDF axes, delivery
 * needs <em>no</em> ee code — both {@code EmailService} and {@code S3UploadService}
 * already live in core; S3 is withheld from community purely by this entitlement
 * gate. Routing exposure through
 * {@link com.ldapportal.core.entitlement.EntitlementService#exposed(Class)} keeps
 * the gate in one place and lets the edition-leak guards cover this enum
 * automatically.</p>
 */
public enum ReportDeliveryMethod implements EditionScoped {

    EMAIL(null),
    S3(Entitlement.GOVERNANCE);

    private final Entitlement requiredEntitlement;

    ReportDeliveryMethod(Entitlement requiredEntitlement) {
        this.requiredEntitlement = requiredEntitlement;
    }

    /** {@inheritDoc} {@code null} for the core surface ({@link #EMAIL}). */
    @Override
    public Entitlement requiredEntitlement() {
        return requiredEntitlement;
    }
}
