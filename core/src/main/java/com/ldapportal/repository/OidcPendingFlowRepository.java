// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.OidcPendingFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface OidcPendingFlowRepository extends JpaRepository<OidcPendingFlow, String> {

    /** Delete flows created before the given cutoff. Called opportunistically
     *  on each new authorize request so we don't need a scheduled sweeper. */
    @Modifying
    @Query("DELETE FROM OidcPendingFlow f WHERE f.createdAt < :cutoff")
    int deleteExpired(@Param("cutoff") OffsetDateTime cutoff);
}
