// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra.repository;

import com.ldapportal.entra.entity.EntraSyncState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EntraSyncStateRepository extends JpaRepository<EntraSyncState, UUID> {
}
