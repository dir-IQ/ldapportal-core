// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.EnableDisableValueType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LdapUserService} using an in-memory LDAP server.
 * No external LDAP infrastructure required.
 */
@ExtendWith(MockitoExtension.class)
class LdapUserServiceTest {

    @Mock private EncryptionService encryptionService;

    private LdapConnectionFactory connectionFactory;
    private LdapUserService        userService;
    private InMemoryDirectoryServer inMemoryServer;
    private DirectoryConnection     dc;

    private static final String BASE_DN   = "dc=example,dc=com";
    private static final String USERS_OU  = "ou=users,dc=example,dc=com";
    private static final String BIND_DN   = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASS = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(null);
        inMemoryServer = new InMemoryDirectoryServer(config);

        // Populate base structure
        inMemoryServer.add(new Entry(BASE_DN,
                new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", "example")));
        inMemoryServer.add(new Entry(USERS_OU,
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "users")));

        inMemoryServer.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        // Empty interceptor chain — the BaselinePlans fallback path
        // is what these tests exercise. This is the "no behaviour
        // change from the SPI" invariant: every assertion below
        // continues to pass exactly as it did pre-SPI.
        userService = new LdapUserService(
                connectionFactory,
                new com.ldapportal.core.provisioning.ProvisioningInterceptorChain(java.util.List.of()),
                new com.ldapportal.core.provisioning.PlanExecutor(connectionFactory),
                new com.ldapportal.core.provisioning.UserReadEnricherChain(java.util.List.of()));
        dc = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        inMemoryServer.shutDown(true);
    }

    // ── searchUsers ───────────────────────────────────────────────────────────

    @Test
    void searchUsers_noEntries_returnsEmpty() {
        List<LdapUser> result = userService.searchUsers(dc, "(objectClass=inetOrgPerson)", null);
        assertThat(result).isEmpty();
    }

    @Test
    void searchUsers_withMatchingEntry_returnsUser() throws Exception {
        addUser("cn=Alice,ou=users,dc=example,dc=com", "Alice", "Smith");

        List<LdapUser> result = userService.searchUsers(dc, "(cn=Alice)", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDn()).isEqualTo("cn=Alice,ou=users,dc=example,dc=com");
        assertThat(result.get(0).getValues("cn")).contains("Alice");
    }

    @Test
    void searchUsers_filterNarrowsResults() throws Exception {
        addUser("cn=Alice,ou=users,dc=example,dc=com", "Alice", "Smith");
        addUser("cn=Bob,ou=users,dc=example,dc=com",   "Bob",   "Jones");

        List<LdapUser> result = userService.searchUsers(dc, "(cn=Bob)", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDn()).contains("Bob");
    }

    @Test
    void searchUsers_customBaseDn_searchesUnderThatBase() throws Exception {
        addUser("cn=Alice,ou=users,dc=example,dc=com", "Alice", "Smith");

        // searching under BASE_DN finds Alice; searching under a different OU finds nothing
        List<LdapUser> found = userService.searchUsers(dc, "(cn=Alice)", USERS_OU);
        assertThat(found).hasSize(1);
    }

    // ── getUser ───────────────────────────────────────────────────────────────

    @Test
    void getUser_existingEntry_returnsUser() throws Exception {
        String dn = "cn=Alice,ou=users,dc=example,dc=com";
        addUser(dn, "Alice", "Smith");

        LdapUser user = userService.getUser(dc, dn);

        assertThat(user.getDn()).isEqualTo(dn);
        assertThat(user.getValues("cn")).contains("Alice");
        assertThat(user.getValues("sn")).contains("Smith");
    }

    @Test
    void getUser_notFound_throwsResourceNotFoundException() {
        assertThatThrownBy(() -> userService.getUser(dc, "cn=NoOne,ou=users,dc=example,dc=com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getUser_withAttributeFilter_returnsOnlyRequestedAttributes() throws Exception {
        String dn = "cn=Alice,ou=users,dc=example,dc=com";
        addUser(dn, "Alice", "Smith");

        LdapUser user = userService.getUser(dc, dn, "cn");

        assertThat(user.getValues("cn")).contains("Alice");
        // sn was not requested so it should be absent
        assertThat(user.getValues("sn")).isEmpty();
    }

    // ── createUser ────────────────────────────────────────────────────────────

    @Test
    void createUser_addsEntryToDirectory() throws Exception {
        String dn = "cn=Carol,ou=users,dc=example,dc=com";

        userService.createUser(dc, dn, Map.of(
                "objectClass", List.of("top", "inetOrgPerson"),
                "cn",          List.of("Carol"),
                "sn",          List.of("Davis")));

        LdapUser fetched = userService.getUser(dc, dn);
        assertThat(fetched.getDn()).isEqualTo(dn);
        assertThat(fetched.getValues("cn")).contains("Carol");
    }

    // ── updateUser ────────────────────────────────────────────────────────────

    @Test
    void updateUser_replacesAttributeValue() throws Exception {
        String dn = "cn=Dave,ou=users,dc=example,dc=com";
        addUser(dn, "Dave", "Old");

        userService.updateUser(dc, dn,
                List.of(new Modification(ModificationType.REPLACE, "sn", "New")));

        LdapUser updated = userService.getUser(dc, dn);
        assertThat(updated.getValues("sn")).containsExactly("New");
    }

    // ── deleteUser ────────────────────────────────────────────────────────────

    @Test
    void deleteUser_removesEntryFromDirectory() throws Exception {
        String dn = "cn=Eve,ou=users,dc=example,dc=com";
        addUser(dn, "Eve", "Taylor");

        userService.deleteUser(dc, dn);

        assertThatThrownBy(() -> userService.getUser(dc, dn))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── enableUser / disableUser ──────────────────────────────────────────────

    @Test
    void enableUser_setsEnableAttributeToTrueString() throws Exception {
        String dn = "cn=Frank,ou=users,dc=example,dc=com";
        addUserWithStatus(dn, "Frank", "Wilson", "accountStatus", "disabled");

        DirectoryConnection dcWithEnable = buildDcWithEnableDisable(
                "accountStatus", EnableDisableValueType.BOOLEAN, null, null);

        userService.enableUser(dcWithEnable, dn);

        LdapUser u = userService.getUser(dcWithEnable, dn);
        assertThat(u.getValues("accountstatus")).containsExactly("TRUE");
    }

    @Test
    void disableUser_setsDisableAttributeToFalseString() throws Exception {
        String dn = "cn=Grace,ou=users,dc=example,dc=com";
        addUserWithStatus(dn, "Grace", "Hall", "accountStatus", "TRUE");

        DirectoryConnection dcWithEnable = buildDcWithEnableDisable(
                "accountStatus", EnableDisableValueType.BOOLEAN, null, null);

        userService.disableUser(dcWithEnable, dn);

        LdapUser u = userService.getUser(dcWithEnable, dn);
        assertThat(u.getValues("accountstatus")).containsExactly("FALSE");
    }

    @Test
    void enableUser_noAttributeConfigured_throwsLdapOperationException() throws Exception {
        String dn = "cn=Hank,ou=users,dc=example,dc=com";
        addUser(dn, "Hank", "Young");

        DirectoryConnection dcNoAttr = buildDc(); // no enable/disable attribute

        assertThatThrownBy(() -> userService.enableUser(dcNoAttr, dn))
                .isInstanceOf(LdapOperationException.class);
    }

    // ── moveUser ──────────────────────────────────────────────────────────────

    @Test
    void moveUser_movesEntryToNewParent() throws Exception {
        String oldDn = "cn=Ivy,ou=users,dc=example,dc=com";
        addUser(oldDn, "Ivy", "Lee");

        // Create a second OU to move into
        inMemoryServer.add(new Entry("ou=staff,dc=example,dc=com",
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "staff")));

        userService.moveUser(dc, oldDn, "ou=staff,dc=example,dc=com");

        // Entry should now be at new DN
        LdapUser moved = userService.getUser(dc, "cn=Ivy,ou=staff,dc=example,dc=com");
        assertThat(moved.getValues("cn")).contains("Ivy");

        // Old DN should be gone
        assertThatThrownBy(() -> userService.getUser(dc, oldDn))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addUser(String dn, String cn, String sn) throws Exception {
        inMemoryServer.add(new Entry(dn,
                new Attribute("objectClass", "top", "inetOrgPerson"),
                new Attribute("cn", cn),
                new Attribute("sn", sn)));
    }

    private void addUserWithStatus(String dn, String cn, String sn,
                                   String statusAttr, String statusValue) throws Exception {
        inMemoryServer.add(new Entry(dn,
                new Attribute("objectClass", "top", "inetOrgPerson"),
                new Attribute("cn", cn),
                new Attribute("sn", sn),
                new Attribute(statusAttr, statusValue)));
    }

    private DirectoryConnection buildDc() {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(UUID.randomUUID());
        d.setDisplayName("test-ldap");
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

    private DirectoryConnection buildDcWithEnableDisable(String attr,
                                                          EnableDisableValueType valueType,
                                                          String enableVal,
                                                          String disableVal) {
        DirectoryConnection d = buildDc();
        d.setEnableDisableAttribute(attr);
        d.setEnableDisableValueType(valueType);
        d.setEnableValue(enableVal);
        d.setDisableValue(disableVal);
        return d;
    }
}
