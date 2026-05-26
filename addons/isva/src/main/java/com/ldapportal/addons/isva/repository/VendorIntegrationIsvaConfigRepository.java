// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.repository;

import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository keyed by {@code directory_connection_id}. The interceptor
 * looks up the config on each plan computation; the cost is one
 * indexed PK fetch, so no caching layer here yet.
 *
 * <p>When no row exists for a directory, the addon is effectively
 * disabled for that directory and the interceptor falls back to
 * {@link com.ldapportal.core.provisioning.BaselinePlans}.</p>
 */
public interface VendorIntegrationIsvaConfigRepository
        extends JpaRepository<VendorIntegrationIsvaConfig, UUID> {
}
