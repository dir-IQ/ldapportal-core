// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/**
 * In-scope source entries a reconcile found that its set doesn't manage, for
 * one reason.
 *
 * @param reason  the {@link com.ldapportal.entity.enums.SyncNotInSetReason} name
 * @param label   what it means, for display
 * @param count   entries with this reason
 * @param samples up to the run's sample limit of their source DNs, in processing order
 */
public record SyncNotInSetGroup(String reason, String label, int count, List<String> samples) {
}
