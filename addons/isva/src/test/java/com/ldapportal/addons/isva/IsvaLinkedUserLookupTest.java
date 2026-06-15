// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * End-to-end lookup tests against an in-memory UnboundID directory.
 * Schema is disabled ({@code config.setSchema(null)}) so the test
 * fixture can use {@code objectClass: secUser} without IBM's
 * secschema.def actually being loaded — what we're verifying is
 * the search filter + DN return path, not schema compliance.
 */
@ExtendWith(MockitoExtension.class)
class IsvaLinkedUserLookupTest {

    @Mock private EncryptionService encryptionService;

    private InMemoryDirectoryServer inMemoryServer;
    private LdapConnectionFactory connectionFactory;
    private IsvaLinkedUserLookup lookup;
    private DirectoryConnection dir;

    private static final String BASE_DN = "dc=example,dc=com";
    private static final String PEOPLE_OU = "ou=people,dc=example,dc=com";
    private static final String MGMT_BASE = "secAuthority=Default,o=acme,c=us";
    private static final String BIND_DN = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASS = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(
                BASE_DN, "o=acme,c=us");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(null);
        inMemoryServer = new InMemoryDirectoryServer(config);
        inMemoryServer.add(new Entry(BASE_DN,
                new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", "example")));
        inMemoryServer.add(new Entry(PEOPLE_OU,
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        inMemoryServer.add(new Entry("o=acme,c=us",
                new Attribute("objectClass", "top", "organization"),
                new Attribute("o", "acme")));
        inMemoryServer.add(new Entry(MGMT_BASE,
                new Attribute("objectClass", "top", "organizationalUnit", "secAuthority"),
                new Attribute("secAuthority", "Default"),
                new Attribute("ou", "Default")));
        inMemoryServer.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        lookup = new IsvaLinkedUserLookup(connectionFactory);
        dir = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        inMemoryServer.shutDown(true);
    }

    @Test
    void findSecUserDn_returnsTheMatchingDn() throws Exception {
        // Seed a demographic entry + paired secUser entry under the
        // management DIT pointing back via secDN.
        String demographicDn = "uid=alice," + PEOPLE_OU;
        String secUserDn = "secUUID=abc-123," + MGMT_BASE;
        inMemoryServer.add(new Entry(demographicDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "alice"),
                new Attribute("cn", "Alice"), new Attribute("sn", "A")));
        inMemoryServer.add(new Entry(secUserDn,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "abc-123"),
                new Attribute("secDN", demographicDn),
                new Attribute("secLogin", "alice")));

        Optional<String> got = lookup.findSecUserDn(dir, MGMT_BASE, demographicDn);

        assertThat(got).isPresent();
        // UnboundID's getDN() returns the entry's DN; the format
        // matches but case may not — compare case-insensitively.
        assertThat(got.get()).isEqualToIgnoringCase(secUserDn);
    }

    @Test
    void findSecUserDn_emptyWhenNoMatch() {
        Optional<String> got = lookup.findSecUserDn(dir, MGMT_BASE,
                "uid=orphan," + PEOPLE_OU);

        assertThat(got).isEmpty();
    }

    @Test
    void findSecUserDn_emptyWhenSecDnExistsButObjectClassDoesNot() throws Exception {
        // Defensive: a stray entry with the secDN attribute but no
        // objectClass: secUser must NOT match. ISVA only honours
        // secUser-typed entries.
        String demographicDn = "uid=bob," + PEOPLE_OU;
        inMemoryServer.add(new Entry(demographicDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "bob"),
                new Attribute("cn", "Bob"), new Attribute("sn", "B")));
        // Plain organizationalUnit-typed entry with a secDN value —
        // shouldn't be picked up.
        inMemoryServer.add(new Entry("cn=stray," + MGMT_BASE,
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("cn", "stray"),
                new Attribute("ou", "stray"),
                new Attribute("secDN", demographicDn)));

        Optional<String> got = lookup.findSecUserDn(dir, MGMT_BASE, demographicDn);

        assertThat(got).isEmpty();
    }

    @Test
    void findSecUserDn_returnsFirstWhenDuplicates() throws Exception {
        // Two secUser entries with the same secDN — directory-side
        // data corruption. Lookup logs a warning + returns one of
        // them (we don't pin which since search-order is up to
        // the server).
        String demographicDn = "uid=duped," + PEOPLE_OU;
        inMemoryServer.add(new Entry(demographicDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "duped"),
                new Attribute("cn", "Dup"), new Attribute("sn", "Ed")));
        inMemoryServer.add(new Entry("secUUID=copy-1," + MGMT_BASE,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "copy-1"),
                new Attribute("secDN", demographicDn)));
        inMemoryServer.add(new Entry("secUUID=copy-2," + MGMT_BASE,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "copy-2"),
                new Attribute("secDN", demographicDn)));

        Optional<String> got = lookup.findSecUserDn(dir, MGMT_BASE, demographicDn);

        assertThat(got).isPresent();
        assertThat(got.get()).contains(",secAuthority=Default,o=acme,c=us");
    }

    @Test
    void findSecUserDn_throwsOnBlankManagementDit() {
        assertThatThrownBy(() -> lookup.findSecUserDn(dir, "  ",
                "uid=alice," + PEOPLE_OU))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managementDitBaseDn");
    }

    @Test
    void findSecUserDn_wrapsLdapErrorsAsLdapOperationException() {
        // Point at a base DN that doesn't exist → UnboundID returns
        // a NO_SUCH_OBJECT LDAPException. The lookup wraps it.
        assertThatThrownBy(() -> lookup.findSecUserDn(dir,
                "o=does-not-exist", "uid=alice," + PEOPLE_OU))
                .isInstanceOf(LdapOperationException.class)
                .hasMessageContaining("Failed to look up secUser");
    }

    private DirectoryConnection buildDc() {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(UUID.randomUUID());
        d.setDisplayName("test-ldap");
        d.setDirectoryType(DirectoryType.OPENLDAP);
        d.setHost("localhost");
        d.setPort(inMemoryServer.getListenPort());
        d.setSslMode(SslMode.NONE);
        d.setTrustAllCerts(false);
        d.setBindDn(BIND_DN);
        d.setBindPasswordEncrypted("enc-placeholder");
        d.setBaseDn(BASE_DN);
        d.setPoolMinSize(1);
        d.setPoolMaxSize(3);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setPagingSize(100);
        return d;
    }
}
