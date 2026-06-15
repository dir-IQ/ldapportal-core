// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import java.util.UUID;

public record ProfileApproverResponse(
        UUID accountId,
        String username,
        String email) {
}
