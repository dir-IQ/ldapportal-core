// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.directory;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DirectoryProviderRegistryTest {

    /** Minimal stub provider for testing. */
    private static class StubProvider implements DirectoryProvider {
        private final List<DirectoryType> types;
        StubProvider(DirectoryType... types) { this.types = List.of(types); }
        @Override public List<DirectoryType> supportedTypes() { return types; }
        @Override public List<DirectoryUser> searchUsers(DirectoryConnection dc, String f, int m) { return List.of(); }
        @Override public DirectoryUser getUser(DirectoryConnection dc, String id) { return null; }
        @Override public List<DirectoryGroup> searchGroups(DirectoryConnection dc, String f, int m) { return List.of(); }
        @Override public List<String> getGroupMembers(DirectoryConnection dc, String gid) { return List.of(); }
        @Override public List<DirectoryAuditEvent> pollAuditEvents(DirectoryConnection dc, OffsetDateTime s) { return List.of(); }
        @Override public String testConnection(DirectoryConnection dc) { return null; }
    }

    @Test
    void getProvider_returnsByType() {
        var ldap = new StubProvider(DirectoryType.GENERIC, DirectoryType.OPENLDAP);
        var entra = new StubProvider(DirectoryType.ENTRA_ID);
        var registry = new DirectoryProviderRegistry(List.of(ldap, entra));

        assertThat(registry.getProvider(DirectoryType.GENERIC)).isSameAs(ldap);
        assertThat(registry.getProvider(DirectoryType.OPENLDAP)).isSameAs(ldap);
        assertThat(registry.getProvider(DirectoryType.ENTRA_ID)).isSameAs(entra);
    }

    @Test
    void getProvider_byConnection_fallsBackToGeneric() {
        var ldap = new StubProvider(DirectoryType.GENERIC);
        var registry = new DirectoryProviderRegistry(List.of(ldap));

        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(null); // unset

        assertThat(registry.getProvider(dc)).isSameAs(ldap);
    }

    @Test
    void getProvider_throwsForUnregisteredType() {
        var ldap = new StubProvider(DirectoryType.GENERIC);
        var registry = new DirectoryProviderRegistry(List.of(ldap));

        assertThatThrownBy(() -> registry.getProvider(DirectoryType.ENTRA_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ENTRA_ID");
    }

    @Test
    void hasProvider_returnsTrueForRegistered() {
        var ldap = new StubProvider(DirectoryType.GENERIC);
        var registry = new DirectoryProviderRegistry(List.of(ldap));

        assertThat(registry.hasProvider(DirectoryType.GENERIC)).isTrue();
        assertThat(registry.hasProvider(DirectoryType.ENTRA_ID)).isFalse();
    }
}
