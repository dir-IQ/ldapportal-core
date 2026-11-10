// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.schema.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The orphan flag against a directory whose schema types {@code secDN} as a
 * DN, as IBM's does: the server then matches {@code (secDN=...)} by DN
 * equality, so a secUser whose {@code secDN} is written differently from the
 * user's DN (spacing, case, escaping) is still found, and the enricher must
 * pair it rather than flag the user as orphaned.
 */
@ExtendWith(MockitoExtension.class)
class IsvaUserReadEnricherDnMatchTest {

    private static final String BASE = "dc=example,dc=com";
    private static final String PEOPLE = "ou=people,dc=example,dc=com";
    private static final String MGMT = "secAuthority=Default,o=acme,c=us";
    private static final String BIND_DN = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASS = "adminpass";

    /** IBM's secDN is DN-syntax with distinguishedNameMatch; a minimal secUser / secAuthority. */
    private static final String[] ISVA_SCHEMA = {
            "dn: cn=schema",
            "objectClass: top",
            "objectClass: ldapSubentry",
            "objectClass: subschema",
            "cn: schema",
            "attributeTypes: ( 1.3.6.1.4.1.99999.1.1 NAME 'secDN' EQUALITY distinguishedNameMatch "
                    + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 SINGLE-VALUE )",
            "attributeTypes: ( 1.3.6.1.4.1.99999.1.2 NAME 'secUUID' EQUALITY caseIgnoreMatch "
                    + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )",
            "attributeTypes: ( 1.3.6.1.4.1.99999.1.3 NAME 'secLogin' EQUALITY caseIgnoreMatch "
                    + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )",
            "attributeTypes: ( 1.3.6.1.4.1.99999.1.4 NAME 'secAuthority' EQUALITY caseIgnoreMatch "
                    + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )",
            "objectClasses: ( 1.3.6.1.4.1.99999.2.1 NAME 'secUser' SUP top AUXILIARY "
                    + "MAY ( secUUID $ secDN $ secLogin ) )",
            "objectClasses: ( 1.3.6.1.4.1.99999.2.2 NAME 'secAuthorityInfo' SUP top STRUCTURAL "
                    + "MUST secAuthority )",
            "objectClasses: ( 1.3.6.1.4.1.99999.2.3 NAME 'secUserEntry' SUP top STRUCTURAL "
                    + "MUST secUUID )",
    };

    @Mock private EncryptionService encryptionService;
    @Mock private VendorIntegrationIsvaConfigRepository configRepo;

    private InMemoryDirectoryServer server;
    private LdapConnectionFactory connectionFactory;
    private IsvaUserReadEnricher enricher;
    private DirectoryConnection dir;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE, "o=acme,c=us");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(Schema.mergeSchemas(Schema.getDefaultStandardSchema(), new Schema(new Entry(ISVA_SCHEMA))));
        server = new InMemoryDirectoryServer(config);
        server.add(new Entry(BASE, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        server.add(new Entry(PEOPLE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        server.add(new Entry("o=acme,c=us", new Attribute("objectClass", "top", "organization"),
                new Attribute("o", "acme")));
        server.add(new Entry(MGMT, new Attribute("objectClass", "top", "secAuthorityInfo"),
                new Attribute("secAuthority", "Default")));
        server.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        enricher = new IsvaUserReadEnricher(configRepo, connectionFactory);
        dir = new DirectoryConnection();
        dir.setId(UUID.randomUUID());
        dir.setDisplayName("test-ldap");
        dir.setDirectoryType(DirectoryType.OPENLDAP);
        dir.setHost("localhost");
        dir.setPort(server.getListenPort());
        dir.setSslMode(SslMode.NONE);
        dir.setBindDn(BIND_DN);
        dir.setBindPasswordEncrypted("enc-placeholder");
        dir.setBaseDn(BASE);
        dir.setPoolMinSize(1);
        dir.setPoolMaxSize(3);
        dir.setPoolConnectTimeoutSeconds(5);
        dir.setPoolResponseTimeoutSeconds(10);
        dir.setPagingSize(100);

        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(dir.getId());
        cfg.setEnabled(true);
        cfg.setTopologyMode(IsvaTopologyMode.LINKED);
        cfg.setManagementDitBaseDn(MGMT);
        cfg.setUserContainerDn(MGMT);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        server.shutDown(true);
    }

    private void addSecUser(String uuid, String secDn) throws Exception {
        server.add(new Entry("secUUID=" + uuid + "," + MGMT,
                new Attribute("objectClass", "top", "secUserEntry", "secUser"),
                new Attribute("secUUID", uuid), new Attribute("secDN", secDn),
                new Attribute("secLogin", uuid)));
    }

    private LdapUser enriched(String userDn) {
        return enricher.enrichBatch(dir,
                List.of(new LdapUser(userDn, Map.of("uid", List.of("x"))))).get(0);
    }

    @Test
    void secDnWrittenWithSpacesAfterCommas_isPaired_notOrphaned() throws Exception {
        addSecUser("jsmith", "uid=jsmith, ou=people, dc=example, dc=com");

        LdapUser user = enriched("uid=jsmith," + PEOPLE);
        assertThat(user.getFirstValue(IsvaUserReadEnricher.HAS_ACCOUNT)).isEqualTo("true");
        // The joined columns are filled too: a falsely orphaned user had none.
        assertThat(user.getFirstValue("isva.secuserdn")).isEqualToIgnoringCase("secUUID=jsmith," + MGMT);
        assertThat(user.getFirstValue("isva.seclogin")).isEqualTo("jsmith");
    }

    @Test
    void secDnWithDifferentAttributeNameCase_andSpaces_isPaired() throws Exception {
        addSecUser("adoe", "UID=adoe , OU=People,DC=Example,DC=Com");

        LdapUser user = enriched("uid=adoe," + PEOPLE);
        assertThat(user.getFirstValue(IsvaUserReadEnricher.HAS_ACCOUNT)).isEqualTo("true");
        assertThat(user.getFirstValue("isva.secuserdn")).isEqualToIgnoringCase("secUUID=adoe," + MGMT);
    }

    @Test
    void userWithNoSecUser_isStillOrphaned() throws Exception {
        addSecUser("someoneelse", "uid=someoneelse," + PEOPLE);

        LdapUser user = enriched("uid=nobody," + PEOPLE);
        assertThat(user.getFirstValue(IsvaUserReadEnricher.HAS_ACCOUNT)).isEqualTo("false");
        // A real orphan has no secUser, so nothing to show in the joined columns.
        assertThat(user.getFirstValue("isva.secuserdn")).isNull();
    }
}
