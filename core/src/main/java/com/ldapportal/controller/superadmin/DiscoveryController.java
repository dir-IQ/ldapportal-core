// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.dto.discovery.CommitDiscoveryRequest;
import com.ldapportal.dto.discovery.CommitDiscoveryResponse;
import com.ldapportal.dto.discovery.DiscoveryProposalResponse;
import com.ldapportal.dto.discovery.DiscoveryRequest;
import com.ldapportal.service.DirectoryDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/superadmin/directories/{directoryId}/discover")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class DiscoveryController {

    private final DirectoryDiscoveryService discoveryService;

    /**
     * Scan the directory and return a discovery proposal.
     * POST because the scan can be slow — we don't want HTTP caching.
     */
    @PostMapping
    public DiscoveryProposalResponse discover(
            @PathVariable UUID directoryId,
            @RequestBody(required = false) DiscoveryRequest request) {
        if (request == null) {
            request = new DiscoveryRequest(null, null, null);
        }
        return discoveryService.discover(directoryId, request);
    }

    /**
     * Commit a reviewed discovery proposal (create profiles + base DNs).
     */
    @PostMapping("/commit")
    public CommitDiscoveryResponse commit(
            @PathVariable UUID directoryId,
            @RequestBody CommitDiscoveryRequest request) {
        return discoveryService.commit(directoryId, request);
    }
}
