// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReplicationLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReplicationLinkRepository extends JpaRepository<ReplicationLink, UUID> {

    /**
     * All enabled links whose source is the given directory. The
     * enqueuer calls this on every write to fan out to matching targets.
     * Returning an empty list on the no-link case is the common path
     * (most directories have zero replication links configured).
     */
    List<ReplicationLink> findAllBySourceDirectoryIdAndEnabledTrue(UUID sourceDirectoryId);
}
