// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SyncSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link SyncSet}. Phase-0 mapping only.
 */
public interface SyncSetRepository extends JpaRepository<SyncSet, UUID> {

    List<SyncSet> findAllByLinkId(UUID linkId);

    List<SyncSet> findAllByEnabledTrue();
}
