// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.discovery;

import java.util.List;

/**
 * Result of committing a discovery proposal.
 */
public record CommitDiscoveryResponse(
        int profilesCreated,
        int userBaseDnsAdded,
        int groupBaseDnsAdded,
        List<String> warnings
) {}
