// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.entitlement.License;
import com.ldapportal.core.entitlement.LimitType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * License & entitlement gauges (Phase 3b observability) — the "license overlay"
 * that complements the always-on {@link InventoryMetrics} counts.
 *
 * <p>Designed to stay <b>dormant in community</b> with no edition branching, via
 * the model's own sentinels: a limit of {@code Long.MAX_VALUE} (unlimited) and an
 * expiry of {@link Instant#MAX} (never) mean the corresponding series are simply
 * <b>not registered</b>. So a community install emits the entitlement 0/1 flags,
 * the {@code info} descriptor, and {@code expired=0}, but no expiry timestamp and
 * no quota series.</p>
 *
 * <p>The conditional series ({@code expiry}, {@code usage_limit}) are registered
 * from the license as seen at startup; a mid-run license change updates the
 * always-on gauges on the next {@link #refresh()} but only surfaces a new
 * expiry/limit <em>series</em> after a restart (license installs are
 * restart-time in practice). {@code grace_state} is intentionally out of scope
 * here — the expiry timestamp plus {@code expired} cover the essential alerts.</p>
 */
@Component
@Slf4j
public class LicenseMetrics {

    private static final String ENTITLEMENT = "ldapportal.license.entitlement";
    private static final String INFO = "ldapportal.license.info";
    private static final String EXPIRED = "ldapportal.license.expired";
    private static final String EXPIRY = "ldapportal.license.expiry.timestamp.seconds";
    private static final String USAGE_LIMIT = "ldapportal.usage.limit";

    private final EntitlementService entitlements;

    private final EnumMap<Entitlement, AtomicLong> entitlementGranted = new EnumMap<>(Entitlement.class);
    private final AtomicLong expired = new AtomicLong();
    private final AtomicLong expiryEpochSec = new AtomicLong();
    private final EnumMap<LimitType, AtomicLong> limitValues = new EnumMap<>(LimitType.class);
    private boolean expiryRegistered;

    public LicenseMetrics(EntitlementService entitlements, MeterRegistry registry) {
        this.entitlements = entitlements;
        bind(registry, entitlements.current());
    }

    private void bind(MeterRegistry registry, License lic) {
        // Entitlement flags — always, all values (bounded, low cardinality).
        for (Entitlement e : Entitlement.values()) {
            AtomicLong granted = new AtomicLong(lic.has(e) ? 1L : 0L);
            entitlementGranted.put(e, granted);
            Gauge.builder(ENTITLEMENT, granted, AtomicLong::doubleValue)
                    .description("License entitlement granted (1) or withheld (0)")
                    .tag("entitlement", e.name())
                    .register(registry);
        }

        // Install descriptor (Prometheus "info" pattern): constant 1, current labels.
        Gauge.builder(INFO, () -> 1.0)
                .description("Active license descriptor")
                .tag("edition", lic.edition().name())
                .tag("signed", String.valueOf(lic.signature() != null))
                .register(registry);

        // Hard expired flag — always; community (never-expires) reports 0.
        expired.set(lic.isExpired(Instant.now()) ? 1L : 0L);
        Gauge.builder(EXPIRED, expired, AtomicLong::doubleValue)
                .description("1 if the license is past its expiry instant, else 0")
                .register(registry);

        // Expiry timestamp — only when there's a real expiry (skip Instant.MAX).
        if (!lic.expiresAt().equals(Instant.MAX)) {
            expiryRegistered = true;
            expiryEpochSec.set(lic.expiresAt().getEpochSecond());
            Gauge.builder(EXPIRY, expiryEpochSec, AtomicLong::doubleValue)
                    .description("License expiry as a Unix timestamp; alert on (metric - time())")
                    .baseUnit("seconds")
                    .register(registry);
        }

        // Quota limits — only finite ones (skip Long.MAX_VALUE = unlimited).
        for (LimitType type : LimitType.values()) {
            long limit = lic.limitFor(type);
            if (limit != Long.MAX_VALUE) {
                AtomicLong value = new AtomicLong(limit);
                limitValues.put(type, value);
                Gauge.builder(USAGE_LIMIT, value, AtomicLong::doubleValue)
                        .description("Licensed quota for a resource (pairs with ldapportal_inventory_*)")
                        .tag("resource", type.name().toLowerCase(Locale.ROOT))
                        .register(registry);
            }
        }
    }

    /** Prime at startup so the first scrape reflects the current license. */
    @PostConstruct
    void primeOnStartup() {
        refresh();
    }

    @Scheduled(initialDelayString = "${ldapportal.metrics.refresh-ms:15000}",
               fixedDelayString = "${ldapportal.metrics.refresh-ms:15000}")
    public void refresh() {
        try {
            License lic = entitlements.current();
            entitlementGranted.forEach((e, g) -> g.set(lic.has(e) ? 1L : 0L));
            expired.set(lic.isExpired(Instant.now()) ? 1L : 0L);
            if (expiryRegistered && !lic.expiresAt().equals(Instant.MAX)) {
                expiryEpochSec.set(lic.expiresAt().getEpochSecond());
            }
            for (Map.Entry<LimitType, AtomicLong> entry : limitValues.entrySet()) {
                long limit = lic.limitFor(entry.getKey());
                if (limit != Long.MAX_VALUE) {
                    entry.getValue().set(limit);
                }
            }
        } catch (RuntimeException e) {
            // Metrics refresh must never disrupt the app; keep the last snapshot.
            log.debug("License metrics refresh failed: {}", e.toString());
        }
    }
}
