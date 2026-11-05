// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OudCookieChangelogStrategyTest {

    private static final String COOKIE = "dc=example,dc=com:0000018c2f3a1b2c00010000000a;";

    private final OudCookieChangelogStrategy strategy = new OudCookieChangelogStrategy();

    @Test
    void buildCookieSearchRequest_subtreeOverChangelogRoot_withCriticalCookieControl() throws Exception {
        SearchRequest req = strategy.buildCookieSearchRequest("cn=changelog", COOKIE, 500);

        // Cookie-mode entries are replicationCSN=…,<domain>,cn=changelog — two
        // levels down — so ONE scope would return nothing.
        assertThat(req.getBaseDN()).isEqualTo("cn=changelog");
        assertThat(req.getScope()).isEqualTo(SearchScope.SUB);
        assertThat(req.getFilter().toString()).isEqualTo("(objectClass=*)");
        assertThat(req.getSizeLimit()).isEqualTo(500);
        assertThat(req.getAttributeList()).contains("changeLogCookie", "targetDN", "changeType", "newRDN");

        Control c = req.getControl(OudCookieChangelogStrategy.ECL_COOKIE_CONTROL_OID);
        assertThat(c).isNotNull();
        assertThat(c.isCritical()).isTrue();
        // Value = the cookie as a BER OCTET STRING.
        assertThat(ASN1OctetString.decodeAsOctetString(c.getValue().getValue()).stringValue())
                .isEqualTo(COOKIE);
    }

    @Test
    void buildCookieSearchRequest_nullCookie_sendsEmptyCookie() throws Exception {
        SearchRequest req = strategy.buildCookieSearchRequest("cn=changelog", null, 10);

        Control c = req.getControl(OudCookieChangelogStrategy.ECL_COOKIE_CONTROL_OID);
        assertThat(ASN1OctetString.decodeAsOctetString(c.getValue().getValue()).stringValue()).isEmpty();
    }

    @Test
    void extractEntryId_isTheEntryCookie() {
        assertThat(strategy.extractEntryId(entry("add", "uid=a,dc=example,dc=com", COOKIE))).isEqualTo(COOKIE);
        assertThat(strategy.extractEntryId(entry("add", "uid=a,dc=example,dc=com", null))).isNull();
    }

    @Test
    void extractTargetDnAndRename_matchDseeFormat() {
        SearchResultEntry e = new SearchResultEntry(
                "replicationCSN=0000018c2f3a1b2c00010000000a,dc=example,dc=com,cn=changelog",
                new Attribute[] {
                        new Attribute("changeType", "modrdn"),
                        new Attribute("targetDN", "uid=a,ou=staging,dc=example,dc=com"),
                        new Attribute("newRDN", "uid=a"),
                        new Attribute("newSuperior", "ou=people,dc=example,dc=com"),
                        new Attribute("changeLogCookie", COOKIE)
                });

        assertThat(strategy.extractTargetDn(e)).isEqualTo("uid=a,ou=staging,dc=example,dc=com");
        assertThat(strategy.extractPostModifyDn(e)).contains("uid=a,ou=people,dc=example,dc=com");
    }

    private static SearchResultEntry entry(String changeType, String targetDn, String cookie) {
        Attribute[] attrs = cookie == null
                ? new Attribute[] {new Attribute("changeType", changeType), new Attribute("targetDN", targetDn)}
                : new Attribute[] {new Attribute("changeType", changeType), new Attribute("targetDN", targetDn),
                        new Attribute("changeLogCookie", cookie)};
        return new SearchResultEntry("replicationCSN=x,dc=example,dc=com,cn=changelog", attrs);
    }
}
