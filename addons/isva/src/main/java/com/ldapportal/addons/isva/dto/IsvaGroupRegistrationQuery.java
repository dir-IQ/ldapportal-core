// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The group DNs a page is showing, to learn which are imported into IVIA. */
public record IsvaGroupRegistrationQuery(@NotNull @Size(max = 5000) List<String> dns) {
}
