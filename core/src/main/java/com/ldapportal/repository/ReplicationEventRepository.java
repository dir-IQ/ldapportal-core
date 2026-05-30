// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReplicationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReplicationEventRepository extends JpaRepository<ReplicationEvent, UUID> {
    // Query methods land in P1 alongside the worker that needs them.
    // P0 only persists events; P1 reads them back in FIFO order.
}
