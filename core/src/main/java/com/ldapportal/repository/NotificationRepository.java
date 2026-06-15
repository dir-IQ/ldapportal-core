// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByAccountIdAndReadFalseOrderByCreatedAtDesc(UUID accountId);

    Page<Notification> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    long countByAccountIdAndReadFalse(UUID accountId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.accountId = :accountId AND n.read = false")
    int markAllReadByAccountId(@Param("accountId") UUID accountId);
}
