// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * How a bulk CSV import behaves when one or more data rows have errors
 * (a missing required attribute, a missing key value, or an invalid DN).
 */
public enum ImportErrorHandling {

    /**
     * Skip rows that have errors and import the rest. The per-row result list
     * still reports each skipped/errored row. This is the legacy behaviour and
     * the default.
     */
    SKIP_ERRORS,

    /**
     * Block the entire import when any row has an error — nothing is written
     * until every row is valid. The result reports the offending rows so they
     * can be fixed and the import re-run.
     */
    ABORT_ON_ERROR
}
