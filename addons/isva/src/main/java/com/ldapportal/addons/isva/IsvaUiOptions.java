// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Addon-owned UI configuration for the ISVA integration page, bound from
 * {@code app.isva.*} (house style — mirrors core's {@code app.*}
 * {@link com.ldapportal.config.AppProperties}). The binding, default, and
 * validation all live here in the addon; core stays ISVA-agnostic.
 *
 * <p>{@code app.isva.exposed-topology-modes} (env
 * {@code APP_ISVA_EXPOSED_TOPOLOGY_MODES}) is a comma-separated subset of
 * {@code inline,linked}; default {@code linked}. UI-only — it narrows what
 * the {@code isva-config} page offers and does <b>not</b> restrict what the
 * config API accepts. When a single mode is exposed the page hides the
 * topology selector and pins that mode.</p>
 *
 * <p>Parsing is lenient: unknown / blank tokens are ignored and an empty
 * result falls back to {@code [LINKED]}, so a typo can't fail boot or brick
 * the page.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.isva")
@Getter
@Setter
public class IsvaUiOptions {

    /** Raw {@code app.isva.exposed-topology-modes}; parsed by
     *  {@link #resolvedTopologyModes()}. */
    private String exposedTopologyModes = "linked";

    /** Parsed + normalised exposed modes, in canonical order ({@code INLINE}
     *  before {@code LINKED}). Never empty. */
    public List<IsvaTopologyMode> resolvedTopologyModes() {
        return parse(exposedTopologyModes);
    }

    static List<IsvaTopologyMode> parse(String raw) {
        Set<IsvaTopologyMode> found = EnumSet.noneOf(IsvaTopologyMode.class);
        if (raw != null) {
            for (String token : raw.split(",")) {
                String t = token.trim().toUpperCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    found.add(IsvaTopologyMode.valueOf(t));
                } catch (IllegalArgumentException ignored) {
                    // Unknown token — skip rather than fail boot on a typo.
                }
            }
        }
        if (found.isEmpty()) {
            found.add(IsvaTopologyMode.LINKED);
        }
        // Canonical order regardless of the order the operator typed them.
        List<IsvaTopologyMode> ordered = new ArrayList<>(2);
        if (found.contains(IsvaTopologyMode.INLINE)) {
            ordered.add(IsvaTopologyMode.INLINE);
        }
        if (found.contains(IsvaTopologyMode.LINKED)) {
            ordered.add(IsvaTopologyMode.LINKED);
        }
        return List.copyOf(ordered);
    }
}
