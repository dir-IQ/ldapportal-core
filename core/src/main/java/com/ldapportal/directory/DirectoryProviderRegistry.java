// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.directory;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry that maps {@link DirectoryType} to the appropriate {@link DirectoryProvider}.
 */
@Service
@Slf4j
public class DirectoryProviderRegistry {

    private final Map<DirectoryType, DirectoryProvider> providers = new HashMap<>();

    public DirectoryProviderRegistry(List<DirectoryProvider> allProviders) {
        for (DirectoryProvider provider : allProviders) {
            for (DirectoryType type : provider.supportedTypes()) {
                providers.put(type, provider);
            }
        }
        log.info("Directory provider registry initialized: {}", providers.keySet());
    }

    /**
     * Get the provider for a given directory type.
     *
     * @throws IllegalArgumentException if no provider is registered for the type
     */
    public DirectoryProvider getProvider(DirectoryType type) {
        DirectoryProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("No directory provider registered for type: " + type);
        }
        return provider;
    }

    /**
     * Get the provider for a given directory connection.
     */
    public DirectoryProvider getProvider(DirectoryConnection dc) {
        DirectoryType type = dc.getDirectoryType();
        if (type == null) type = DirectoryType.GENERIC;
        return getProvider(type);
    }

    /**
     * Check if a provider is registered for the given type.
     */
    public boolean hasProvider(DirectoryType type) {
        return providers.containsKey(type);
    }
}
