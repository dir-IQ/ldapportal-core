// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.DashboardLayout;
import com.ldapportal.repository.DashboardLayoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes per-account dashboard layouts. The JSON shape is opaque to
 * the server — the frontend store owns versioning and default-merging — but
 * we do light structural validation on writes so malformed payloads can't
 * corrupt the row.
 */
@Service
@RequiredArgsConstructor
public class DashboardLayoutService {

    private final DashboardLayoutRepository repo;

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> get(UUID accountId) {
        return repo.findById(accountId).map(DashboardLayout::getLayout);
    }

    @Transactional
    public void save(UUID accountId, Map<String, Object> layout) {
        validate(layout);
        DashboardLayout entity = repo.findById(accountId).orElseGet(() -> {
            DashboardLayout e = new DashboardLayout();
            e.setAccountId(accountId);
            return e;
        });
        entity.setLayout(layout);
        repo.save(entity);
    }

    @Transactional
    public void delete(UUID accountId) {
        repo.deleteById(accountId);
    }

    /**
     * Reject obviously-malformed layouts. We don't enforce the full schema here
     * (the frontend's mergeWithDefaults is tolerant of unknown keys by design);
     * we just guard against nonsense payloads like arrays, strings, or missing
     * version markers.
     */
    private void validate(Map<String, Object> layout) {
        if (layout == null) {
            throw new IllegalArgumentException("Layout body is required");
        }
        Object version = layout.get("version");
        if (!(version instanceof Number n) || n.intValue() != 1) {
            throw new IllegalArgumentException("Unsupported layout version: " + version);
        }
    }
}
