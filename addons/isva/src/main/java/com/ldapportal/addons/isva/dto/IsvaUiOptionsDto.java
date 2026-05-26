// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;

import java.util.List;

/**
 * UI options for the ISVA integration page. Currently just the topology
 * modes the operator may select from (see {@code EXPOSED_ISVA_TOPOLOGY_MODES});
 * a single-element list tells the page to hide the topology selector.
 */
public record IsvaUiOptionsDto(List<IsvaTopologyMode> exposedTopologyModes) {
}
