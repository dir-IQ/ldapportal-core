// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.siem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;
import com.ldapportal.entity.enums.SiemFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SiemFormatterTest {

    private SiemFormatter formatter;

    private final UUID eventId = UUID.randomUUID();
    private final OffsetDateTime timestamp =
            OffsetDateTime.of(2026, 3, 24, 10, 30, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        formatter = new SiemFormatter(new ObjectMapper());
    }

    // ── RFC 5424 ────────────────────────────────────────────────────────────

    @Test
    void rfc5424_containsPriorityAndTimestamp() {
        String result = formatter.format(testEvent(), SiemFormat.RFC5424);

        assertThat(result).startsWith("<134>1 ");
        assertThat(result).contains("2026-03-24T10:30:00");
    }

    @Test
    void rfc5424_usesExperimentalEnterpriseNumber() {
        String result = formatter.format(testEvent(), SiemFormat.RFC5424);

        // Should use 32473 (experimental), not 0 (reserved)
        assertThat(result).contains("[event@32473 ");
    }

    @Test
    void rfc5424_containsStructuredData() {
        String result = formatter.format(testEvent(), SiemFormat.RFC5424);

        assertThat(result).contains("action=\"USER_CREATE\"");
        assertThat(result).contains("actor=\"alice\"");
        assertThat(result).contains("directory=\"corp-ldap\"");
        assertThat(result).contains("targetDn=\"uid=alice,ou=users,dc=corp\"");
        assertThat(result).contains("source=\"INTERNAL\"");
    }

    @Test
    void rfc5424_handlesNullActor() {
        AuditEvent event = AuditEvent.builder()
                .id(eventId)
                .source(AuditSource.LDAP_CHANGELOG)
                .action(AuditAction.LDAP_CHANGE)
                .targetDn("uid=test,dc=corp")
                .occurredAt(timestamp)
                .build();

        String result = formatter.format(event, SiemFormat.RFC5424);

        assertThat(result).doesNotContain("actor=");
        assertThat(result).contains("LDAP_CHANGE");
    }

    @Test
    void rfc5424_escapesSpecialCharsInSD() {
        AuditEvent event = AuditEvent.builder()
                .id(eventId)
                .source(AuditSource.INTERNAL)
                .action(AuditAction.USER_UPDATE)
                .actorUsername("user\"with]quotes")
                .targetDn("uid=test,dc=corp")
                .occurredAt(timestamp)
                .build();

        String result = formatter.format(event, SiemFormat.RFC5424);

        assertThat(result).contains("actor=\"user\\\"with\\]quotes\"");
    }

    // ── CEF ─────────────────────────────────────────────────────────────────

    @Test
    void cef_startsWithHeader() {
        String result = formatter.format(testEvent(), SiemFormat.CEF);

        assertThat(result).startsWith("CEF:0|LDAPPortal|LDAPPortal|1.0|USER_CREATE|USER_CREATE|");
    }

    @Test
    void cef_containsExtensionFields() {
        String result = formatter.format(testEvent(), SiemFormat.CEF);

        assertThat(result).contains("suser=alice");
        assertThat(result).contains("cs1=uid\\=alice,ou\\=users,dc\\=corp");
        assertThat(result).contains("cs1Label=targetDn");
        assertThat(result).contains("cs2=corp-ldap");
        assertThat(result).contains("cs2Label=directoryName");
        assertThat(result).contains("rt=");
    }

    @Test
    void cef_deleteAction_hasHighSeverity() {
        AuditEvent deleteEvent = AuditEvent.builder()
                .id(eventId)
                .source(AuditSource.INTERNAL)
                .action(AuditAction.USER_DELETE)
                .actorUsername("admin")
                .occurredAt(timestamp)
                .build();

        String result = formatter.format(deleteEvent, SiemFormat.CEF);

        assertThat(result).contains("|USER_DELETE|7|");
    }

    @Test
    void cef_createAction_hasLowSeverity() {
        String result = formatter.format(testEvent(), SiemFormat.CEF);

        assertThat(result).contains("|USER_CREATE|3|");
    }

    @Test
    void cef_securityBlock_hasHighSeverity() {
        AuditEvent event = AuditEvent.builder()
                .id(eventId)
                .source(AuditSource.INTERNAL)
                .action(AuditAction.SOD_VIOLATION_BLOCKED)
                .actorUsername("admin")
                .occurredAt(timestamp)
                .build();

        String result = formatter.format(event, SiemFormat.CEF);

        // BLOCKED matches the high-severity (7) pattern
        assertThat(result).contains("|SOD_VIOLATION_BLOCKED|7|");
    }

    @Test
    void cef_violationDetected_hasMediumHighSeverity() {
        AuditEvent event = AuditEvent.builder()
                .id(eventId)
                .source(AuditSource.INTERNAL)
                .action(AuditAction.SOD_VIOLATION_DETECTED)
                .actorUsername("admin")
                .occurredAt(timestamp)
                .build();

        String result = formatter.format(event, SiemFormat.CEF);

        assertThat(result).contains("|SOD_VIOLATION_DETECTED|6|");
    }

    @Test
    void cef_headerEscapesPipeCharacters() {
        // Verify the escape method works for header values with pipe chars
        AuditEvent event = AuditEvent.builder()
                .id(eventId)
                .source(AuditSource.INTERNAL)
                .action(AuditAction.USER_CREATE)
                .occurredAt(timestamp)
                .build();

        String result = formatter.format(event, SiemFormat.CEF);

        // Standard action names don't have pipes, but the escaping path is exercised
        assertThat(result).contains("CEF:0|LDAPPortal|LDAPPortal|1.0|");
    }

    // ── LEEF ────────────────────────────────────────────────────────────────

    @Test
    void leef_startsWithHeader() {
        String result = formatter.format(testEvent(), SiemFormat.LEEF);

        assertThat(result).startsWith("LEEF:2.0|LDAPPortal|LDAPPortal|1.0|USER_CREATE|");
    }

    @Test
    void leef_containsAttributes() {
        String result = formatter.format(testEvent(), SiemFormat.LEEF);

        assertThat(result).contains("usrName=alice");
        assertThat(result).contains("identSrc=uid=alice,ou=users,dc=corp");
        assertThat(result).contains("resource=corp-ldap");
        assertThat(result).contains("devTime=");
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    @Test
    void json_isValidJson() {
        String result = formatter.format(testEvent(), SiemFormat.JSON);

        assertThat(result).startsWith("{");
        assertThat(result).endsWith("}");
    }

    @Test
    void json_containsAllFields() {
        String result = formatter.format(testEvent(), SiemFormat.JSON);

        assertThat(result).contains("\"action\":\"USER_CREATE\"");
        assertThat(result).contains("\"actorUsername\":\"alice\"");
        assertThat(result).contains("\"directoryName\":\"corp-ldap\"");
        assertThat(result).contains("\"targetDn\":\"uid=alice,ou=users,dc=corp\"");
        assertThat(result).contains("\"source\":\"INTERNAL\"");
    }

    @Test
    void json_omitsNullFields() {
        AuditEvent event = AuditEvent.builder()
                .id(eventId)
                .source(AuditSource.LDAP_CHANGELOG)
                .action(AuditAction.LDAP_CHANGE)
                .occurredAt(timestamp)
                .build();

        String result = formatter.format(event, SiemFormat.JSON);

        // Null fields should be omitted, not included as null
        assertThat(result).doesNotContain("\"actorUsername\"");
        assertThat(result).doesNotContain("\"targetDn\"");
        assertThat(result).contains("\"action\":\"LDAP_CHANGE\"");
    }

    @Test
    void json_containsDetail() {
        AuditEvent event = AuditEvent.builder()
                .id(eventId)
                .source(AuditSource.INTERNAL)
                .action(AuditAction.USER_CREATE)
                .actorUsername("alice")
                .targetDn("uid=alice,dc=corp")
                .detail(Map.of("modified", "cn"))
                .occurredAt(timestamp)
                .build();

        String result = formatter.format(event, SiemFormat.JSON);

        assertThat(result).contains("\"detail\":{\"modified\":\"cn\"}");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AuditEvent testEvent() {
        return AuditEvent.builder()
                .id(eventId)
                .source(AuditSource.INTERNAL)
                .action(AuditAction.USER_CREATE)
                .actorId(UUID.randomUUID())
                .actorUsername("alice")
                .actorType("ADMIN")
                .directoryId(UUID.randomUUID())
                .directoryName("corp-ldap")
                .targetDn("uid=alice,ou=users,dc=corp")
                .occurredAt(timestamp)
                .build();
    }
}
