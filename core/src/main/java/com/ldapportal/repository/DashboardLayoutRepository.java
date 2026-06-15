// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.DashboardLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DashboardLayoutRepository extends JpaRepository<DashboardLayout, UUID> {
}
