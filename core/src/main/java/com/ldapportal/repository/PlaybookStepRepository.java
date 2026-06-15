// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.PlaybookStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaybookStepRepository extends JpaRepository<PlaybookStep, UUID> {

    List<PlaybookStep> findAllByPlaybookIdOrderByStepOrderAsc(UUID playbookId);

    void deleteAllByPlaybookId(UUID playbookId);
}
