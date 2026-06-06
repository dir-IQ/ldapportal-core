// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.Membership;
import com.ldapportal.entity.MembershipId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for the {@link Membership} index. Phase-0 mapping only — the
 * row-locked per-identity reads/writes the engine performs are added as it
 * lands.
 */
public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    List<Membership> findAllBySyncSetId(UUID syncSetId);
}
