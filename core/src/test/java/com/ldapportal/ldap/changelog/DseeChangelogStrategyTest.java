// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.ldapportal.entity.AuditDataSource;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DseeChangelogStrategyTest {

    private final DseeChangelogStrategy strategy = new DseeChangelogStrategy();

    // ── buildSearchRequest ───────────────────────────────────────────────────

    @Test
    void buildSearchRequest_noFilter() throws Exception {
        AuditDataSource src = newSource("cn=changelog", null);
        SearchRequest req = strategy.buildSearchRequest(src, 100);

        assertThat(req.getBaseDN()).isEqualTo("cn=changelog");
        assertThat(req.getScope()).isEqualTo(SearchScope.ONE);
        assertThat(req.getFilter().toString()).isEqualTo("(objectClass=changeLogEntry)");
        assertThat(req.getSizeLimit()).isEqualTo(100);
    }

    @Test
    void buildSearchRequest_withBranchFilter() throws Exception {
        AuditDataSource src = newSource("cn=changelog", "ou=users,dc=example,dc=com");
        SearchRequest req = strategy.buildSearchRequest(src, 50);

        assertThat(req.getFilter().toString())
                .isEqualTo("(&(objectClass=changeLogEntry)(targetDN=ou=users,dc=example,dc=com*))");
    }

    // ── extractEntryId ───────────────────────────────────────────────────────

    @Test
    void extractEntryId_returnsChangeNumber() {
        SearchResultEntry entry = entry(
                new Attribute("changeNumber", "42"));

        assertThat(strategy.extractEntryId(entry)).isEqualTo("42");
    }

    @Test
    void extractEntryId_returnsNullWhenMissing() {
        SearchResultEntry entry = entry();

        assertThat(strategy.extractEntryId(entry)).isNull();
    }

    // ── extractTargetDn ──────────────────────────────────────────────────────

    @Test
    void extractTargetDn_returnsTargetDN() {
        SearchResultEntry entry = entry(
                new Attribute("targetDN", "uid=john,ou=users,dc=test"));

        assertThat(strategy.extractTargetDn(entry)).isEqualTo("uid=john,ou=users,dc=test");
    }

    // ── extractDetail ────────────────────────────────────────────────────────

    @Test
    void extractDetail_basicModify() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modify"),
                new Attribute("changes", "replace: mail\nmail: new@test.com\n-"),
                new Attribute("creatorsName", "cn=admin"));

        Map<String, Object> detail = strategy.extractDetail(entry);
        assertThat(detail).containsEntry("changeType", "modify");
        assertThat(detail).containsEntry("creatorsName", "cn=admin");
        assertThat(detail).containsKey("changes");
        assertThat(detail).doesNotContainKey("newRDN");
    }

    @Test
    void extractDetail_modrdn() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modrdn"),
                new Attribute("newRDN", "uid=jane"),
                new Attribute("deleteOldRDN", "TRUE"),
                new Attribute("newSuperior", "ou=people,dc=test"),
                new Attribute("creatorsName", "cn=admin"));

        Map<String, Object> detail = strategy.extractDetail(entry);
        assertThat(detail).containsEntry("newRDN", "uid=jane");
        assertThat(detail).containsEntry("deleteOldRDN", "TRUE");
        assertThat(detail).containsEntry("newSuperior", "ou=people,dc=test");
    }

    // ── extractOccurredAt / timestamp parsing ────────────────────────────────

    @Test
    void extractOccurredAt_validTimestamp() {
        SearchResultEntry entry = entry(
                new Attribute("changeTime", "20260319143022Z"));

        OffsetDateTime result = strategy.extractOccurredAt(entry);
        assertThat(result.getYear()).isEqualTo(2026);
        assertThat(result.getMonthValue()).isEqualTo(3);
        assertThat(result.getDayOfMonth()).isEqualTo(19);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void extractOccurredAt_nullTimestamp_fallsBackToNow() {
        SearchResultEntry entry = entry();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime result = strategy.extractOccurredAt(entry);
        assertThat(result).isAfterOrEqualTo(before.minusSeconds(1));
    }

    // ── isRecordable ─────────────────────────────────────────────────────────

    @Test
    void isRecordable_alwaysTrue() {
        SearchResultEntry entry = entry();
        assertThat(strategy.isRecordable(entry)).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static AuditDataSource newSource(String baseDn, String branchFilterDn) {
        AuditDataSource src = new AuditDataSource();
        src.setChangelogBaseDn(baseDn);
        src.setBranchFilterDn(branchFilterDn);
        src.setChangelogFormat(ChangelogFormat.DSEE_CHANGELOG);
        return src;
    }

    private static SearchResultEntry entry(Attribute... attrs) {
        return new SearchResultEntry("changeNumber=1,cn=changelog", attrs);
    }
}
