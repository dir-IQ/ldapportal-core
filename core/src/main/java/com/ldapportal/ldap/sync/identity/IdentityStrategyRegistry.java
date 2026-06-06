// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import com.ldapportal.entity.enums.DirectoryType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the {@link IdentityStrategy} for a directory type. Strategies are
 * contributed as Spring beans, so a later edition/addon can register a
 * vendor-specific strategy without core changes.
 */
@Component
public class IdentityStrategyRegistry {

    private final List<IdentityStrategy> strategies;

    public IdentityStrategyRegistry(List<IdentityStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    /**
     * @return the strategy handling {@code type}.
     * @throws IllegalArgumentException if no strategy supports the type.
     */
    public IdentityStrategy forType(DirectoryType type) {
        return strategies.stream()
                .filter(s -> s.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No identity strategy for directory type " + type));
    }
}
