// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.discovery;

import com.ldapportal.dto.profile.CreateProfileRequest;

import java.util.List;

/**
 * Request body for committing a reviewed discovery proposal.
 */
public record CommitDiscoveryRequest(
        List<CreateProfileRequest> profiles,
        List<String> userBaseDns,
        List<String> groupBaseDns
) {}
