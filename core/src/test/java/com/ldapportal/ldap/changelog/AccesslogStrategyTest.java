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

class AccesslogStrategyTest {

    private final AccesslogStrategy strategy = new AccesslogStrategy();

    // ── buildSearchRequest ───────────────────────────────────────────────────

    @Test
    void buildSearchRequest_noFilter() throws Exception {
        AuditDataSource src = newSource("cn=accesslog", null);
        SearchRequest req = strategy.buildSearchRequest(src, 200);

        assertThat(req.getBaseDN()).isEqualTo("cn=accesslog");
        assertThat(req.getScope()).isEqualTo(SearchScope.ONE);
        assertThat(req.getFilter().toString())
                .isEqualTo("(&(objectClass=auditWriteObject)(reqResult=0))");
        assertThat(req.getSizeLimit()).isEqualTo(200);
    }

    @Test
    void buildSearchRequest_withBranchFilter() throws Exception {
        AuditDataSource src = newSource("cn=accesslog", "ou=users,dc=example,dc=com");
        SearchRequest req = strategy.buildSearchRequest(src, 100);

        assertThat(req.getFilter().toString())
                .isEqualTo("(&(objectClass=auditWriteObject)(reqResult=0)(reqDN=*ou=users,dc=example,dc=com))");
    }

    // ── extractEntryId ───────────────────────────────────────────────────────

    @Test
    void extractEntryId_returnsReqStart() {
        SearchResultEntry entry = entry(
                new Attribute("reqStart", "20260319143022.000006Z#000001#000#000000"));

        assertThat(strategy.extractEntryId(entry))
                .isEqualTo("20260319143022.000006Z#000001#000#000000");
    }

    @Test
    void extractEntryId_returnsNullWhenMissing() {
        SearchResultEntry entry = entry();
        assertThat(strategy.extractEntryId(entry)).isNull();
    }

    // ── extractTargetDn ──────────────────────────────────────────────────────

    @Test
    void extractTargetDn_returnsReqDN() {
        SearchResultEntry entry = entry(
                new Attribute("reqDN", "uid=john,ou=users,dc=example,dc=com"));

        assertThat(strategy.extractTargetDn(entry))
                .isEqualTo("uid=john,ou=users,dc=example,dc=com");
    }

    // ── extractDetail ────────────────────────────────────────────────────────

    @Test
    void extractDetail_modify() {
        SearchResultEntry entry = entry(
                new Attribute("reqType", "modify"),
                new Attribute("reqMod", "replace: mail", "mail: new@test.com", "-"),
                new Attribute("reqAuthzID", "dn:cn=admin,dc=example,dc=com"));

        Map<String, Object> detail = strategy.extractDetail(entry);

        assertThat(detail).containsEntry("changeType", "modify");
        assertThat(detail.get("changes")).isNotNull();
        assertThat(detail).containsEntry("creatorsName", "cn=admin,dc=example,dc=com");
        assertThat(detail).doesNotContainKey("newRDN");
    }

    @Test
    void extractDetail_modrdn() {
        SearchResultEntry entry = entry(
                new Attribute("reqType", "modrdn"),
                new Attribute("reqNewRDN", "uid=jane"),
                new Attribute("reqDeleteOldRDN", "TRUE"),
                new Attribute("reqNewSuperior", "ou=people,dc=test"),
                new Attribute("reqAuthzID", "dn:cn=admin"));

        Map<String, Object> detail = strategy.extractDetail(entry);

        assertThat(detail).containsEntry("changeType", "modrdn");
        assertThat(detail).containsEntry("newRDN", "uid=jane");
        assertThat(detail).containsEntry("deleteOldRDN", "TRUE");
        assertThat(detail).containsEntry("newSuperior", "ou=people,dc=test");
        assertThat(detail).containsEntry("creatorsName", "cn=admin");
    }

    @Test
    void extractDetail_add() {
        SearchResultEntry entry = entry(
                new Attribute("reqType", "add"),
                new Attribute("reqAuthzID", "dn:cn=manager,dc=test"));

        Map<String, Object> detail = strategy.extractDetail(entry);
        assertThat(detail).containsEntry("changeType", "add");
        assertThat(detail).containsEntry("creatorsName", "cn=manager,dc=test");
    }

    @Test
    void extractDetail_delete() {
        SearchResultEntry entry = entry(
                new Attribute("reqType", "delete"));

        Map<String, Object> detail = strategy.extractDetail(entry);
        assertThat(detail).containsEntry("changeType", "delete");
    }

    @Test
    void extractDetail_withReqOld() {
        SearchResultEntry entry = entry(
                new Attribute("reqType", "modify"),
                new Attribute("reqOld", "mail: old@test.com"));

        Map<String, Object> detail = strategy.extractDetail(entry);
        assertThat(detail).containsKey("reqOld");
    }

    // ── extractOccurredAt ────────────────────────────────────────────────────

    @Test
    void extractOccurredAt_parsesReqStartWithSerial() {
        SearchResultEntry entry = entry(
                new Attribute("reqStart", "20260319143022.000006Z#000001#000#000000"));

        OffsetDateTime result = strategy.extractOccurredAt(entry);
        assertThat(result.getYear()).isEqualTo(2026);
        assertThat(result.getMonthValue()).isEqualTo(3);
        assertThat(result.getDayOfMonth()).isEqualTo(19);
        assertThat(result.getHour()).isEqualTo(14);
        assertThat(result.getMinute()).isEqualTo(30);
        assertThat(result.getSecond()).isEqualTo(22);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void extractOccurredAt_parsesReqStartWithoutSerial() {
        SearchResultEntry entry = entry(
                new Attribute("reqStart", "20260101120000.000000Z"));

        OffsetDateTime result = strategy.extractOccurredAt(entry);
        assertThat(result.getYear()).isEqualTo(2026);
        assertThat(result.getMonthValue()).isEqualTo(1);
        assertThat(result.getDayOfMonth()).isEqualTo(1);
    }

    @Test
    void extractOccurredAt_nullFallsBackToNow() {
        SearchResultEntry entry = entry();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime result = strategy.extractOccurredAt(entry);
        assertThat(result).isAfterOrEqualTo(before.minusSeconds(1));
    }

    // ── reqAuthzID prefix stripping ──────────────────────────────────────────

    @Test
    void extractDetail_stripsMultiplePrefixFormats() {
        // Standard "dn:" prefix
        SearchResultEntry entry1 = entry(
                new Attribute("reqType", "add"),
                new Attribute("reqAuthzID", "dn:cn=admin,dc=test"));
        assertThat(strategy.extractDetail(entry1)).containsEntry("creatorsName", "cn=admin,dc=test");

        // "dn: " with space
        SearchResultEntry entry2 = entry(
                new Attribute("reqType", "add"),
                new Attribute("reqAuthzID", "dn: cn=admin,dc=test"));
        assertThat(strategy.extractDetail(entry2)).containsEntry("creatorsName", "cn=admin,dc=test");

        // No prefix (uncommon but possible)
        SearchResultEntry entry3 = entry(
                new Attribute("reqType", "add"),
                new Attribute("reqAuthzID", "cn=admin,dc=test"));
        assertThat(strategy.extractDetail(entry3)).containsEntry("creatorsName", "cn=admin,dc=test");
    }

    // ── isRecordable ─────────────────────────────────────────────────────────

    @Test
    void isRecordable_alwaysTrue() {
        SearchResultEntry entry = entry();
        assertThat(strategy.isRecordable(entry)).isTrue();
    }

    // ── parseReqStart edge cases ─────────────────────────────────────────────

    @Test
    void parseReqStart_invalidFallsBackToNow() {
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime result = AccesslogStrategy.parseReqStart("not-a-timestamp");
        assertThat(result).isAfterOrEqualTo(before.minusSeconds(1));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static AuditDataSource newSource(String baseDn, String branchFilterDn) {
        AuditDataSource src = new AuditDataSource();
        src.setChangelogBaseDn(baseDn);
        src.setBranchFilterDn(branchFilterDn);
        src.setChangelogFormat(ChangelogFormat.OPENLDAP_ACCESSLOG);
        return src;
    }

    private static SearchResultEntry entry(Attribute... attrs) {
        return new SearchResultEntry("reqStart=20260319143022.000006Z,cn=accesslog", attrs);
    }
}
