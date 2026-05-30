// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class ReplicatingLdapInterfaceTest {

    @Mock private ReplicationEnqueuer enqueuer;

    private InMemoryDirectoryServer server;
    private LDAPConnection           realConn;
    private ReplicatingLdapInterface wrapper;
    private UUID                     directoryId;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=test,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=test,dc=com", "adminpass");
        // Seed the base entry so add/modify tests have a parent to work
        // against. setSchema(null) keeps the in-memory server lenient
        // about attribute matching rules — sufficient for this test
        // which only cares about whether the wrapper passes the
        // operation through and captures on success.
        config.setSchema(null);
        server = new InMemoryDirectoryServer(config);
        server.add(new Entry("dc=test,dc=com",
                new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", "test")));
        server.startListening();

        realConn = new LDAPConnection("localhost", server.getListenPort(),
                "cn=admin,dc=test,dc=com", "adminpass");

        directoryId = UUID.randomUUID();
        wrapper = new ReplicatingLdapInterface(realConn, enqueuer, directoryId);
    }

    @AfterEach
    void tearDown() {
        if (realConn != null) realConn.close();
        if (server != null) server.shutDown(true);
    }

    @Test
    void add_successful_capturesOnce() throws LDAPException {
        LDAPResult result = wrapper.add("uid=alice,dc=test,dc=com",
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Alice"),
                new Attribute("sn", "Smith"));

        assertThat(result.getResultCode()).isEqualTo(ResultCode.SUCCESS);

        ArgumentCaptor<CapturedWrite> cap = ArgumentCaptor.forClass(CapturedWrite.class);
        verify(enqueuer).enqueue(eq(directoryId), cap.capture());
        CapturedWrite cw = cap.getValue();
        assertThat(cw.operation()).hasToString("ADD");
        assertThat(cw.dn()).isEqualTo("uid=alice,dc=test,dc=com");
        assertThat(cw.attributes()).hasSize(3);
    }

    @Test
    void add_failed_doesNotCapture() {
        // Same DN twice — second add returns ENTRY_ALREADY_EXISTS, and
        // the SDK throws LDAPException for that.
        try {
            wrapper.add("dc=test,dc=com",  // already exists from setUp
                    new Attribute("objectClass", "top", "domain"),
                    new Attribute("dc", "test"));
        } catch (LDAPException expected) {
            // Expected — the SDK throws on non-success. The capture
            // path runs after a successful result, so a thrown
            // exception means we never reach it.
        }
        verify(enqueuer, never()).enqueue(any(), any());
    }

    @Test
    void modify_successful_capturesOnce() throws LDAPException {
        wrapper.add(new Entry("uid=bob,dc=test,dc=com",
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Bob"),
                new Attribute("sn", "Jones")));

        wrapper.modify("uid=bob,dc=test,dc=com",
                new Modification(ModificationType.REPLACE, "cn", "Robert"));

        verify(enqueuer, times(2)).enqueue(eq(directoryId), any());
    }

    @Test
    void delete_successful_capturesOnce() throws LDAPException {
        wrapper.add(new Entry("uid=carol,dc=test,dc=com",
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Carol"),
                new Attribute("sn", "Davis")));

        wrapper.delete("uid=carol,dc=test,dc=com");

        // First call: add. Second call: delete.
        verify(enqueuer, times(2)).enqueue(eq(directoryId), any());
    }

    @Test
    void search_doesNotCapture() throws LDAPException {
        // Reads must not enqueue. This is the hot path — every group
        // resolution, every audit query, every browse hit. A spurious
        // capture would flood the queue.
        wrapper.search("dc=test,dc=com", SearchScope.BASE, "(objectClass=*)");
        verify(enqueuer, never()).enqueue(any(), any());
    }

    @Test
    void getEntry_doesNotCapture() throws LDAPException {
        SearchResultEntry e = wrapper.getEntry("dc=test,dc=com");
        assertThat(e).isNotNull();
        verify(enqueuer, never()).enqueue(any(), any());
    }

    @Test
    void modifyDn_successful_capturesNewSuperior() throws LDAPException {
        wrapper.add(new Entry("uid=dave,dc=test,dc=com",
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Dave"),
                new Attribute("sn", "Edwards")));

        // ModifyDN without newSuperior: just rename.
        wrapper.modifyDN("uid=dave,dc=test,dc=com", "uid=david", true);

        ArgumentCaptor<CapturedWrite> cap = ArgumentCaptor.forClass(CapturedWrite.class);
        verify(enqueuer, times(2)).enqueue(eq(directoryId), cap.capture());
        // The second capture is the modifyDN.
        CapturedWrite modDn = cap.getAllValues().get(1);
        assertThat(modDn.operation()).hasToString("MODIFY_DN");
        assertThat(modDn.modifyDn().newRdn()).isEqualTo("uid=david");
        assertThat(modDn.modifyDn().deleteOldRdn()).isTrue();
        assertThat(modDn.modifyDn().newSuperiorDn()).isNull();
    }
}
