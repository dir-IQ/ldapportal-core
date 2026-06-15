// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.siem.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.enums.SiemFormat;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts {@link AuditEvent} instances into SIEM-consumable string formats.
 *
 * <p><b>Thread safety note:</b> This component accesses only eagerly-loaded fields
 * of {@code AuditEvent} (all fields are set via the builder before persistence).
 * It must NOT access any lazy-loaded JPA relationships, as events may be passed
 * from async contexts where the persistence context is closed.</p>
 */
@Component
public class SiemFormatter {

    private static final String CEF_VERSION = "0";
    private static final String DEVICE_VENDOR = "LDAPPortal";
    private static final String DEVICE_PRODUCT = "LDAPPortal";
    private static final String DEVICE_VERSION = "1.0";

    private static final String LEEF_VERSION = "2.0";

    private final ObjectMapper objectMapper;

    public SiemFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Formats an audit event into the requested output format.
     */
    public String format(AuditEvent event, SiemFormat fmt) {
        return switch (fmt) {
            case RFC5424 -> toRfc5424(event);
            case CEF -> toCef(event);
            case LEEF -> toLeef(event);
            case JSON -> toJson(event);
        };
    }

    // ── RFC 5424 syslog ─────────────────────────────────────────────────────

    /**
     * RFC 5424 format:
     * {@code <priority>1 timestamp hostname app-name procid msgid [sd-id params] msg}
     */
    private String toRfc5424(AuditEvent event) {
        // PRI = facility(16=local0) * 8 + severity(6=info) = 134
        int priority = 134;
        String timestamp = event.getOccurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String hostname = "-";
        String appName = "LDAPPortal";
        String procId = "-";
        String msgId = event.getAction().name();

        // Structured data — using enterprise number 32473 (experimental/documentation per RFC 5424 §7.2.2)
        StringBuilder sd = new StringBuilder();
        sd.append("[event@32473 ");
        sd.append("id=\"").append(event.getId()).append("\" ");
        sd.append("action=\"").append(event.getAction().name()).append("\" ");
        sd.append("source=\"").append(event.getSource().name()).append("\"");
        if (event.getActorUsername() != null) {
            sd.append(" actor=\"").append(escapeSD(event.getActorUsername())).append("\"");
        }
        if (event.getDirectoryName() != null) {
            sd.append(" directory=\"").append(escapeSD(event.getDirectoryName())).append("\"");
        }
        if (event.getTargetDn() != null) {
            sd.append(" targetDn=\"").append(escapeSD(event.getTargetDn())).append("\"");
        }
        sd.append("]");

        // BOM + message body
        String msg = event.getAction().name();
        if (event.getTargetDn() != null) {
            msg += " " + event.getTargetDn();
        }

        return String.format("<%d>1 %s %s %s %s %s %s %s",
                priority, timestamp, hostname, appName, procId, msgId, sd, msg);
    }

    /** Escape SD-PARAM values per RFC 5424 §6.3.3: escape \, ", ] */
    private String escapeSD(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("]", "\\]");
    }

    // ── CEF (Common Event Format) ───────────────────────────────────────────

    /**
     * CEF format:
     * {@code CEF:0|vendor|product|version|signatureId|name|severity|extension}
     */
    private String toCef(AuditEvent event) {
        String signatureId = escapeCefHeader(event.getAction().name());
        String name = escapeCefHeader(event.getAction().name());
        int severity = cefSeverity(event);

        StringBuilder ext = new StringBuilder();
        ext.append("rt=").append(event.getOccurredAt().toInstant().toEpochMilli());
        if (event.getActorUsername() != null) {
            ext.append(" suser=").append(escapeCefExt(event.getActorUsername()));
        }
        if (event.getTargetDn() != null) {
            ext.append(" cs1=").append(escapeCefExt(event.getTargetDn()));
            ext.append(" cs1Label=targetDn");
        }
        if (event.getDirectoryName() != null) {
            ext.append(" cs2=").append(escapeCefExt(event.getDirectoryName()));
            ext.append(" cs2Label=directoryName");
        }
        if (event.getSource() != null) {
            ext.append(" cs3=").append(event.getSource().name());
            ext.append(" cs3Label=source");
        }

        return String.format("CEF:%s|%s|%s|%s|%s|%s|%s|%s",
                CEF_VERSION, DEVICE_VENDOR, DEVICE_PRODUCT, DEVICE_VERSION,
                signatureId, name, severity, ext);
    }

    private int cefSeverity(AuditEvent event) {
        String action = event.getAction().name();
        // High severity: destructive actions, security blocks, access revocation
        if (action.contains("DELETE") || action.contains("REVOKE")
                || action.contains("BLOCKED") || action.contains("EXPIRED")) {
            return 7;
        }
        // Medium-high: security-relevant status changes
        if (action.contains("REJECTED") || action.contains("FAILED")
                || action.contains("VIOLATION") || action.contains("ESCALATION")
                || action.contains("ORPHAN")) {
            return 6;
        }
        // Low: creation and modification
        if (action.contains("CREATE") || action.contains("UPDATE")
                || action.contains("SUBMITTED") || action.contains("ACTIVATED")) {
            return 3;
        }
        return 5;
    }

    /** Escape CEF header fields: pipe characters must be escaped. */
    private String escapeCefHeader(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }

    /** Escape CEF extension values: backslash, equals, newlines. */
    private String escapeCefExt(String value) {
        return value.replace("\\", "\\\\")
                    .replace("=", "\\=")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }

    // ── LEEF (Log Event Extended Format — IBM QRadar) ────────────────────────

    /**
     * LEEF format:
     * {@code LEEF:2.0|Vendor|Product|Version|EventID|\tkey=value\tkey=value}
     */
    private String toLeef(AuditEvent event) {
        String eventId = event.getAction().name();

        StringBuilder attrs = new StringBuilder();
        attrs.append("devTime=").append(event.getOccurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (event.getActorUsername() != null) {
            attrs.append("\tusrName=").append(event.getActorUsername());
        }
        if (event.getTargetDn() != null) {
            attrs.append("\tidentSrc=").append(event.getTargetDn());
        }
        if (event.getDirectoryName() != null) {
            attrs.append("\tresource=").append(event.getDirectoryName());
        }
        if (event.getSource() != null) {
            attrs.append("\tpolicy=").append(event.getSource().name());
        }
        if (event.getId() != null) {
            attrs.append("\tdevTimeFormat=ISO8601\tsev=").append(leefSeverity(event));
        }

        return String.format("LEEF:%s|%s|%s|%s|%s|%s",
                LEEF_VERSION, DEVICE_VENDOR, DEVICE_PRODUCT, DEVICE_VERSION,
                eventId, attrs);
    }

    private int leefSeverity(AuditEvent event) {
        return cefSeverity(event); // same logic, 0-10 scale
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    private String toJson(AuditEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", event.getId());
        map.put("timestamp", event.getOccurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        map.put("source", event.getSource().name());
        map.put("action", event.getAction().name());
        putIfNotNull(map, "actorId", event.getActorId());
        putIfNotNull(map, "actorUsername", event.getActorUsername());
        putIfNotNull(map, "actorType", event.getActorType());
        putIfNotNull(map, "directoryId", event.getDirectoryId());
        putIfNotNull(map, "directoryName", event.getDirectoryName());
        putIfNotNull(map, "targetDn", event.getTargetDn());
        putIfNotNull(map, "detail", event.getDetail());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            // Fallback: minimal JSON
            return "{\"id\":\"" + event.getId() + "\",\"action\":\"" + event.getAction().name() + "\"}";
        }
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
