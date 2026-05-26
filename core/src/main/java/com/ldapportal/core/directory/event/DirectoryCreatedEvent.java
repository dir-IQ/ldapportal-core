// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.directory.event;

import java.util.UUID;

/**
 * Published by {@code DirectoryConnectionService} immediately after a
 * new directory connection is persisted. Listeners — notably the
 * alerting module's default-rule seeder — can hook this to do one-time
 * setup work per directory without core having to depend on them.
 *
 * <p>This is the first of the core-side SPI events introduced in Phase
 * 3b of the packaging refactor. The pattern: core publishes a neutral
 * domain event; any ee module that cares subscribes via
 * {@code @EventListener}. If no module is loaded (community edition or
 * the module is unlicensed), the event simply fires into the void —
 * which is exactly the right behaviour.</p>
 */
public record DirectoryCreatedEvent(UUID directoryId) {}
