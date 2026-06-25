// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.entity.DirectoryConnection;
import com.unboundid.ldap.sdk.ResultCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Records per-directory LDAP <em>operation</em> latency and outcome (Phase 1 of
 * the self-observability work). Each call through the pooled write/read surface
 * is timed and tagged with the operation verb and a bounded result class, so a
 * single {@code Timer} yields both the latency distribution and the error-rate
 * breakdown.
 *
 * <p>The timer is named {@link #OPERATION_TIMER}. In Prometheus it surfaces as
 * {@code ldapportal_ldap_operations_seconds_*} with tags
 * {@code directory_id} / {@code directory} / {@code type} (see
 * {@link DirectoryMeterTags}) plus:</p>
 * <ul>
 *   <li>{@code operation} — {@code search} / {@code add} / {@code modify} /
 *       {@code modify_dn} / {@code delete} / {@code compare} / {@code bind} /
 *       {@code extended} (a small, fixed set);</li>
 *   <li>{@code result} — {@code success} or a coarse failure class
 *       ({@code not_found} / {@code timeout} / {@code unavailable} /
 *       {@code limit_exceeded} / {@code auth} / {@code invalid} / {@code error}
 *       / {@code other}). <b>Classes, not raw result codes</b>, to keep
 *       cardinality bounded — no DN, filter, or entry data ever becomes a
 *       tag.</li>
 * </ul>
 *
 * <p>{@code com.ldapportal.ldap.MeteredLdapInterface} is the wrapper that calls
 * {@link #record}; the histogram buckets are configured by a {@code MeterFilter}
 * in {@link MetricsConfig}.</p>
 */
@Component
public class LdapOperationMetrics {

    /** Micrometer name of the operation timer (dots → underscores in Prometheus). */
    public static final String OPERATION_TIMER = "ldapportal.ldap.operations";

    private final MeterRegistry registry;

    public LdapOperationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Pre-compute the stable directory tags once per borrowed connection. */
    public Tags directoryTags(DirectoryConnection dc) {
        return DirectoryMeterTags.of(dc);
    }

    /**
     * Record one completed LDAP operation.
     *
     * @param directoryTags the directory dimensions (from {@link #directoryTags})
     * @param operation     the operation verb (bounded set, see class doc)
     * @param result        the result class (bounded set, see class doc)
     * @param durationNanos  elapsed wall time of the call
     */
    public void record(Tags directoryTags, String operation, String result, long durationNanos) {
        registry.timer(OPERATION_TIMER, directoryTags.and("operation", operation).and("result", result))
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Collapse an LDAP {@link ResultCode} into a bounded result class. Mirrors
     * the connection-usable split that {@code LdapConnectionFactory} already uses
     * for its 502-vs-422 mapping, so "unavailable" here lines up with the
     * connection-broken path there.
     */
    public static String resultClass(ResultCode rc) {
        if (rc == null) {
            return "other";
        }
        if (rc == ResultCode.SUCCESS || rc == ResultCode.COMPARE_TRUE
                || rc == ResultCode.COMPARE_FALSE || rc == ResultCode.NO_OPERATION) {
            return "success";
        }
        if (rc == ResultCode.NO_SUCH_OBJECT || rc == ResultCode.NO_SUCH_ATTRIBUTE) {
            return "not_found";
        }
        if (rc == ResultCode.TIMEOUT || rc == ResultCode.TIME_LIMIT_EXCEEDED) {
            return "timeout";
        }
        if (rc == ResultCode.SIZE_LIMIT_EXCEEDED || rc == ResultCode.ADMIN_LIMIT_EXCEEDED) {
            return "limit_exceeded";
        }
        // Semantic auth/data buckets first, so a code that also reports
        // connection-unusable still classifies by its meaning.
        if (rc == ResultCode.INVALID_CREDENTIALS || rc == ResultCode.INSUFFICIENT_ACCESS_RIGHTS
                || rc == ResultCode.INAPPROPRIATE_AUTHENTICATION || rc == ResultCode.AUTHORIZATION_DENIED) {
            return "auth";
        }
        if (rc == ResultCode.CONSTRAINT_VIOLATION || rc == ResultCode.OBJECT_CLASS_VIOLATION
                || rc == ResultCode.ENTRY_ALREADY_EXISTS || rc == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS
                || rc == ResultCode.INVALID_DN_SYNTAX || rc == ResultCode.INVALID_ATTRIBUTE_SYNTAX
                || rc == ResultCode.NOT_ALLOWED_ON_NONLEAF || rc == ResultCode.NOT_ALLOWED_ON_RDN
                || rc == ResultCode.NAMING_VIOLATION || rc == ResultCode.UNDEFINED_ATTRIBUTE_TYPE) {
            return "invalid";
        }
        // Remaining connection-broken codes (SERVER_DOWN, CONNECT_ERROR, ...).
        if (!rc.isConnectionUsable()) {
            return "unavailable";
        }
        return "other";
    }
}
