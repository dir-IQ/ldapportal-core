// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.discovery;

import com.ldapportal.dto.profile.CreateProfileRequest;

import jakarta.validation.Valid;

import java.util.List;

/**
 * Request body for committing a reviewed discovery proposal.
 * The nested {@code @Valid} cascades bean validation into each proposed
 * profile so the wizard's commit path enforces the same constraints as
 * the direct profile-create endpoint.
 */
public record CommitDiscoveryRequest(
        List<@Valid CreateProfileRequest> profiles,
        List<String> userBaseDns,
        List<String> groupBaseDns
) {}
