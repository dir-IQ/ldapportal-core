// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.schema.SchemaElementAction;
import com.ldapportal.dto.schema.SchemaPreviewSummary;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdifService.ParsedRecord;
import com.ldapportal.ldap.schema.OpenDjSchemaWriteStrategy;
import com.ldapportal.ldap.schema.OpenLdapSchemaWriteStrategy;
import com.ldapportal.ldap.schema.SchemaWriteStrategyResolver;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFReader;
import com.unboundid.ldif.LDIFRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SchemaLdifService}'s classification and apply-guard
 * logic. The live schema is mocked with UnboundID's default standard schema; the
 * actual LDAP writes are covered by the Docker-gated integration path.
 */
@ExtendWith(MockitoExtension.class)
class SchemaLdifServiceTest {

    @Mock private LdapConnectionFactory connectionFactory;
    @Mock private LdapSchemaService schemaService;
    @Mock private LdifService ldifService;

    private SchemaLdifService service;

    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SchemaWriteStrategyResolver resolver = new SchemaWriteStrategyResolver(
                List.of(new OpenLdapSchemaWriteStrategy(), new OpenDjSchemaWriteStrategy()));
        service = new SchemaLdifService(connectionFactory, schemaService, ldifService, resolver);
        ReflectionTestUtils.setField(service, "ttlMinutes", 30L);
        ReflectionTestUtils.setField(service, "maxCacheEntries", 20);
    }

    private DirectoryConnection dir(DirectoryType type) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDirectoryType(type);
        dc.setDisplayName("test-" + type);
        dc.setBaseDn("dc=example,dc=com");
        return dc;
    }

    private void stubParse(String ldif) throws Exception {
        List<ParsedRecord> records = new ArrayList<>();
        try (LDIFReader reader = new LDIFReader(
                new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)))) {
            LDIFRecord rec;
            int row = 1;
            while ((rec = reader.readLDIFRecord()) != null) {
                records.add(new ParsedRecord(row++, rec, null));
            }
        }
        when(ldifService.parse(any())).thenReturn(records);
        lenient().when(schemaService.fetchSchema(any())).thenReturn(Schema.getDefaultStandardSchema());
    }

    private InputStream empty() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Test
    void opendj_new_attribute_is_add_new() throws Exception {
        stubParse("""
                dn: cn=schema
                changetype: modify
                add: attributeTypes
                attributeTypes: ( 1.3.6.1.4.1.99999.1.2.3 NAME 'ldapPortalTestAttr' \
                SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
                """);

        SchemaPreviewSummary summary = service.createPreview(dir(DirectoryType.ORACLE_UNIFIED_DIRECTORY),
                empty(), owner);

        assertThat(summary.blocking()).isFalse();
        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.counts().addNew()).isEqualTo(1);
        assertThat(summary.elements()).singleElement()
                .satisfies(el -> {
                    assertThat(el.action()).isEqualTo(SchemaElementAction.ADD_NEW);
                    assertThat(el.name()).isEqualTo("ldapPortalTestAttr");
                });
    }

    @Test
    void opendj_existing_attribute_is_modify_existing() throws Exception {
        // 'cn' is in the standard schema, so re-declaring it is a modify, not an add.
        stubParse("""
                dn: cn=schema
                changetype: modify
                add: attributeTypes
                attributeTypes: ( 2.5.4.3 NAME 'cn' SUP name )
                """);

        SchemaPreviewSummary summary = service.createPreview(dir(DirectoryType.ORACLE_UNIFIED_DIRECTORY),
                empty(), owner);

        assertThat(summary.counts().modifyExisting()).isEqualTo(1);
        assertThat(summary.blocking()).isFalse();
    }

    @Test
    void out_of_scope_dn_is_blocking() throws Exception {
        stubParse("""
                dn: uid=evil,ou=people,dc=example,dc=com
                changetype: modify
                add: attributeTypes
                attributeTypes: ( 1.3.6.1.4.1.99999.1.2.4 NAME 'sneaky' \
                SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )
                """);

        SchemaPreviewSummary summary = service.createPreview(dir(DirectoryType.ORACLE_UNIFIED_DIRECTORY),
                empty(), owner);

        assertThat(summary.blocking()).isTrue();
        assertThat(summary.counts().unsupported()).isEqualTo(1);

        // Applying a blocking preview is refused.
        UUID previewId = UUID.fromString(summary.previewId());
        assertThatThrownBy(() -> service.apply(previewId, owner,
                dir(DirectoryType.ORACLE_UNIFIED_DIRECTORY), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocking");
    }

    @Test
    void openldap_modify_existing_is_unsupported() throws Exception {
        // OpenLDAP can't modify an existing element online — flagged UNSUPPORTED.
        stubParse("""
                dn: cn=core,cn=schema,cn=config
                changetype: modify
                add: olcAttributeTypes
                olcAttributeTypes: ( 2.5.4.3 NAME 'cn' SUP name )
                """);

        SchemaPreviewSummary summary = service.createPreview(dir(DirectoryType.OPENLDAP), empty(), owner);

        assertThat(summary.blocking()).isTrue();
        assertThat(summary.counts().unsupported()).isEqualTo(1);
    }

    @Test
    void openldap_apply_requires_config_credentials() throws Exception {
        stubParse("""
                dn: cn=ldapportal-test,cn=schema,cn=config
                objectClass: olcSchemaConfig
                cn: ldapportal-test
                olcAttributeTypes: ( 1.3.6.1.4.1.99999.1.2.5 NAME 'ldapPortalNew' \
                SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
                """);

        DirectoryConnection dc = dir(DirectoryType.OPENLDAP);
        SchemaPreviewSummary summary = service.createPreview(dc, empty(), owner);
        assertThat(summary.blocking()).isFalse();
        assertThat(summary.counts().addNew()).isEqualTo(1);

        UUID previewId = UUID.fromString(summary.previewId());
        assertThatThrownBy(() -> service.apply(previewId, owner, dc, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config-admin");
    }

    @Test
    void unsupported_vendor_is_rejected() {
        assertThatThrownBy(() -> service.createPreview(dir(DirectoryType.ACTIVE_DIRECTORY), empty(), owner))
                .isInstanceOf(LdapOperationException.class)
                .hasMessageContaining("not supported");
    }
}
