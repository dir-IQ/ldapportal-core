// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.audit.AuditSourceRequest;
import com.ldapportal.entity.AuditDataSource;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.LdapChangelogReader;
import com.ldapportal.repository.AuditDataSourceRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditDataSourceServiceTest {

    private final AuditDataSourceRepository repo = mock(AuditDataSourceRepository.class);
    private final EncryptionService encryptionService = mock(EncryptionService.class);
    private final LdapChangelogReader changelogReader = mock(LdapChangelogReader.class);
    private final AuditDataSourceService service =
            new AuditDataSourceService(repo, encryptionService, changelogReader);

    private AuditSourceRequest request(String slug, String bindPassword) {
        return new AuditSourceRequest(
                "DSEE Audit", "audit.corp.example.com", 636, SslMode.LDAPS, false,
                null, "cn=reader,dc=corp,dc=example,dc=com", bindPassword,
                "cn=changelog", null, ChangelogFormat.DSEE_CHANGELOG, true, slug);
    }

    @Test
    void upsertBySlug_createsWhenAbsent() {
        when(repo.findBySlug("dsee-audit")).thenReturn(Optional.empty());
        when(encryptionService.encrypt("secret")).thenReturn("ENC");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuditDataSourceService.UpsertOutcome outcome =
                service.upsertBySlug("dsee-audit", request("dsee-audit", "secret"));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.response().slug()).isEqualTo("dsee-audit");
        assertThat(outcome.response().bindPasswordSet()).isTrue();
    }

    @Test
    void upsertBySlug_updatesInPlace_preservingSecretWhenOmitted() {
        AuditDataSource existing = new AuditDataSource();
        existing.setId(UUID.randomUUID());
        existing.setSlug("dsee-audit");
        existing.setBindPasswordEncrypted("EXISTING_ENC");
        when(repo.findBySlug("dsee-audit")).thenReturn(Optional.of(existing));
        when(repo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuditDataSourceService.UpsertOutcome outcome =
                service.upsertBySlug("dsee-audit", request("dsee-audit", null));

        assertThat(outcome.created()).isFalse();
        // Omitted bind password preserved — never re-encrypted.
        verify(encryptionService, never()).encrypt(anyString());
        assertThat(existing.getBindPasswordEncrypted()).isEqualTo("EXISTING_ENC");
    }

    @Test
    void exportAll_omitsSecret_keepsSlugAndConfig() {
        AuditDataSource src = new AuditDataSource();
        src.setId(UUID.randomUUID());
        src.setSlug("dsee-audit");
        src.setDisplayName("DSEE Audit");
        src.setHost("audit.corp.example.com");
        src.setPort(636);
        src.setSslMode(SslMode.LDAPS);
        src.setBindDn("cn=reader,dc=corp,dc=example,dc=com");
        src.setBindPasswordEncrypted("ENC");
        src.setChangelogBaseDn("cn=changelog");
        src.setChangelogFormat(ChangelogFormat.DSEE_CHANGELOG);
        src.setEnabled(true);
        when(repo.findAll()).thenReturn(java.util.List.of(src));

        var exports = service.exportAll();

        assertThat(exports).hasSize(1);
        AuditDataSourceService.AuditSourceExport export = exports.get(0);
        assertThat(export.bindPasswordSet()).isTrue();
        assertThat(export.request().slug()).isEqualTo("dsee-audit");
        assertThat(export.request().bindPassword()).isNull();   // never exported
        assertThat(export.request().host()).isEqualTo("audit.corp.example.com");
    }
}
