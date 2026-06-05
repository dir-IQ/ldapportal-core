// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.core.provisioning.BaselinePlans;
import com.ldapportal.core.provisioning.DeletePlan;
import com.ldapportal.core.provisioning.GroupMemberPlan;
import com.ldapportal.core.provisioning.PasswordPlan;
import com.ldapportal.core.provisioning.PasswordSetPayload;
import com.ldapportal.core.provisioning.PlanExecutor;
import com.ldapportal.core.provisioning.ProvisioningContext;
import com.ldapportal.core.provisioning.ProvisioningInterceptor;
import com.ldapportal.core.provisioning.ProvisioningInterceptorChain;
import com.ldapportal.core.provisioning.UserCreatePayload;
import com.ldapportal.core.provisioning.UserCreatePlan;
import com.ldapportal.core.provisioning.UserReadEnricherChain;
import com.ldapportal.dto.ldap.LdifImportResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pins the LDIF-import → provisioning-SPI routing: user entries are created
 * through {@link LdapUserService} so a vendor interceptor (here a stand-in for
 * the ISVA {@code secUser} overlay) fires, while non-user entries, entries that
 * already carry the overlay, and the operator's opt-out all bypass it.
 *
 * <p>Schema validation is disabled on the in-memory server so the test can use
 * the {@code secUser} object class without loading the ISVA schema.</p>
 */
@ExtendWith(MockitoExtension.class)
class LdifServiceProvisioningTest {

    @Mock private EncryptionService encryptionService;

    private LdapConnectionFactory connectionFactory;
    private InMemoryDirectoryServer server;
    private DirectoryConnection dc;

    private static final String BASE = "dc=example,dc=com";
    private static final String BIND = "cn=admin,dc=example,dc=com";
    private static final String PASS = "adminpass";
    private static final String MARKER = "businessCategory";
    private static final String MARKER_VALUE = "ivia-managed";

    /** Stand-in for ISVA: stamps a marker attribute on user creates unless suppressed. */
    private static final class OverlayInterceptor implements ProvisioningInterceptor {
        @Override
        public UserCreatePlan planUserCreate(DirectoryConnection dir, UserCreatePayload payload,
                                             ProvisioningContext ctx) {
            if (ctx.suppressVendorOverlay()) {
                return BaselinePlans.userCreate(payload);
            }
            Map<String, List<String>> attrs = new LinkedHashMap<>(payload.attributes());
            attrs.put(MARKER, List.of(MARKER_VALUE));
            return BaselinePlans.userCreate(UserCreatePayload.of(payload.dn(), attrs));
        }

        @Override
        public DeletePlan planUserDelete(DirectoryConnection dir, String dn, ProvisioningContext ctx) {
            return BaselinePlans.userDelete(dn);
        }

        @Override
        public PasswordPlan planPasswordSet(DirectoryConnection dir, String dn,
                                            PasswordSetPayload payload, ProvisioningContext ctx) {
            return BaselinePlans.passwordSet(dir, dn, payload);
        }

        @Override
        public GroupMemberPlan planGroupMembership(DirectoryConnection dir, String groupDn,
                                                   String memberAttribute, String memberValue,
                                                   ProvisioningContext ctx) {
            return BaselinePlans.groupMembership(groupDn, memberAttribute, memberValue);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.addAdditionalBindCredentials(BIND, PASS);
        config.setSchema(null); // allow arbitrary object classes (secUser)
        server = new InMemoryDirectoryServer(config);
        server.add(new Entry(BASE, new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", "example")));
        server.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService, null);
        dc = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        server.shutDown(true);
    }

    private LdifService ldifWithOverlay() {
        LdapUserService userService = new LdapUserService(connectionFactory,
                new ProvisioningInterceptorChain(List.of(new OverlayInterceptor())),
                new PlanExecutor(connectionFactory),
                new UserReadEnricherChain(List.of()));
        return new LdifService(connectionFactory, userService);
    }

    private LdifImportResult run(LdifService svc, String ldif, boolean suppress) {
        return svc.importLdif(dc, new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)),
                ConflictHandling.SKIP, false, suppress);
    }

    @Test
    void perRowExclusion_skipsOverlayForExcludedRowOnly() {
        LdifService svc = ldifWithOverlay();
        // bob = row 1, kim = row 2. Opt row 2 out of provisioning.
        String ldif = person("bob") + "\n" + person("kim");
        var records = svc.parse(new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)));
        LdifImportResult r = svc.applyParsedRecords(
                dc, records, ConflictHandling.SKIP, false, java.util.Set.of(2));

        assertThat(r.added()).isEqualTo(2);
        assertThat(marker("uid=bob," + BASE)).isEqualTo(MARKER_VALUE); // provisioned
        assertThat(marker("uid=kim," + BASE)).isNull();                // excluded
    }

    private String marker(String dn) {
        SearchResultEntry e = getEntry(dn);
        return e == null ? null : e.getAttributeValue(MARKER);
    }

    private SearchResultEntry getEntry(String dn) {
        try {
            return server.getEntry(dn);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String person(String uid) {
        return "dn: uid=" + uid + "," + BASE + "\n"
                + "objectClass: top\nobjectClass: person\nobjectClass: organizationalPerson\n"
                + "objectClass: inetOrgPerson\ncn: " + uid + "\nsn: " + uid + "\n";
    }

    @Test
    void userEntry_routedThroughInterceptor_getsOverlay() {
        LdifImportResult r = run(ldifWithOverlay(), person("bob"), false);
        assertThat(r.added()).isEqualTo(1);
        assertThat(marker("uid=bob," + BASE)).isEqualTo(MARKER_VALUE);
    }

    @Test
    void suppressVendorOverlay_skipsInterceptor() {
        LdifImportResult r = run(ldifWithOverlay(), person("kim"), true);
        assertThat(r.added()).isEqualTo(1);
        assertThat(marker("uid=kim," + BASE)).isNull();
    }

    @Test
    void nonUserEntry_notRouted_noOverlay() {
        String ou = "dn: ou=teams," + BASE + "\nobjectClass: top\nobjectClass: organizationalUnit\n"
                + "ou: teams\n";
        LdifImportResult r = run(ldifWithOverlay(), ou, false);
        assertThat(r.added()).isEqualTo(1);
        assertThat(marker("ou=teams," + BASE)).isNull();
    }

    @Test
    void entryAlreadyCarryingSecUser_addedRaw_noOverlay() {
        String selfDescribing = "dn: uid=ann," + BASE + "\n"
                + "objectClass: top\nobjectClass: person\nobjectClass: organizationalPerson\n"
                + "objectClass: inetOrgPerson\nobjectClass: secUser\n"
                + "cn: ann\nsn: ann\n";
        LdifImportResult r = run(ldifWithOverlay(), selfDescribing, false);
        assertThat(r.added()).isEqualTo(1);
        assertThat(marker("uid=ann," + BASE)).isNull();
    }

    @Test
    void fileContainingSecUserEntry_disablesOverlayForWholeImport() {
        // A paired export: a plain demographic plus a separate secUser entry.
        // Auto-overlay must stand down so the demographic isn't double-provisioned.
        String ldif = person("dan")
                + "\ndn: uid=dan,ou=secusers," + BASE + "\n"
                + "objectClass: top\nobjectClass: secUser\ncn: dan\n";
        // Parent for the secUser entry.
        run(ldifWithOverlay(), "dn: ou=secusers," + BASE
                + "\nobjectClass: top\nobjectClass: organizationalUnit\nou: secusers\n", false);

        LdifImportResult r = run(ldifWithOverlay(), ldif, false);
        assertThat(r.added()).isEqualTo(2);
        assertThat(marker("uid=dan," + BASE)).isNull();
    }

    private DirectoryConnection buildDc() {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(UUID.randomUUID());
        d.setDisplayName("test-ldap");
        d.setHost("localhost");
        d.setPort(server.getListenPort());
        d.setSslMode(SslMode.NONE);
        d.setTrustAllCerts(false);
        d.setBindDn(BIND);
        d.setBindPasswordEncrypted("enc-placeholder");
        d.setBaseDn(BASE);
        d.setUserObjectClasses(List.of("inetOrgPerson", "organizationalPerson", "person"));
        d.setPoolMinSize(1);
        d.setPoolMaxSize(4);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setPagingSize(100);
        return d;
    }
}
