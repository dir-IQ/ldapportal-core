// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

/**
 * Body for the LDIF preview apply call.
 *
 * @param suppressVendorOverlay import-level opt-out — when true, no user add
 *        provisions a vendor (e.g. ISVA {@code secUser}) overlay.
 * @param excludeOverlayRows per-row opt-outs: 1-based preview row numbers the
 *        operator excluded from provisioning. Ignored when
 *        {@code suppressVendorOverlay} is true. Null/absent means none.
 */
public record ApplyLdifPreviewRequest(
        boolean suppressVendorOverlay,
        List<Integer> excludeOverlayRows) {
}
