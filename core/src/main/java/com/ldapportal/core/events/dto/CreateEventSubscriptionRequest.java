// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.dto;

import com.ldapportal.core.events.enums.ChannelType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateEventSubscriptionRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 500)            String description,
    @NotNull                    ChannelType channelType,
    @NotNull @Valid             DestinationConfigRequest destination,
    List<@NotBlank String>      eventTypeFilter,   // null/empty = all types
    @NotNull                    Boolean enabled
) {}
