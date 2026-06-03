// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

/**
 * A filtered/searched slice of a cached preview's rows.
 *
 * @param rows          rows for this page
 * @param page          0-based page index
 * @param size          page size
 * @param totalFiltered total rows matching the current op/search filter
 */
public record LdifPreviewPage(
        List<LdifPreviewRow> rows,
        int page,
        int size,
        int totalFiltered) {
}
