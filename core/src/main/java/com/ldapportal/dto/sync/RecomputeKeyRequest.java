// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import jakarta.validation.constraints.NotBlank;

/** Operator-triggered recompute of a single key (a source DN or a normalized identity). */
public record RecomputeKeyRequest(@NotBlank String key) {
}
