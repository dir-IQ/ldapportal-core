// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SyncLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link SyncLink}. Phase-0 mapping only — finders the engine
 * needs are added as it lands.
 */
public interface SyncLinkRepository extends JpaRepository<SyncLink, UUID> {

    List<SyncLink> findAllBySourceDirIdAndEnabledTrue(UUID sourceDirId);
}
