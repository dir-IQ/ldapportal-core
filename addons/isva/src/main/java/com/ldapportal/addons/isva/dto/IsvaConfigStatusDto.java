// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import java.util.UUID;

/**
 * Per-directory ISVA configuration summary for list views. One entry per
 * directory that has a config row; a directory absent from the list has
 * no ISVA configuration. {@code enabled} mirrors the config's own flag, so
 * a row can be configured-but-disabled.
 */
public record IsvaConfigStatusDto(UUID directoryId, boolean enabled) {
}
