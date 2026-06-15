// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.repository;

import com.ldapportal.addons.isva.entity.IsvaProfileOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository keyed by {@code profile_id}. The interceptor consults it
 * on each plan computation to decide whether the resolved profile is
 * ISVA-exempt; the cost is one indexed PK fetch.
 *
 * <p>When no row exists for a profile, the profile is treated as
 * {@link com.ldapportal.addons.isva.entity.IsvaProfileOverride#INHERIT}
 * (follow the directory).</p>
 */
public interface IsvaProfileOverrideRepository
        extends JpaRepository<IsvaProfileOverrideEntity, UUID> {
}
