// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.FullLDAPInterface;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.ModifyDNRequest;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * A rename/move must be captured under BOTH the pre- and post-move DN: a set
 * whose scope only contains the old DN would otherwise never learn about the
 * scope-exit (orphaning the target), and one whose scope only contains the new
 * DN would never learn about the scope-entry.
 */
@ExtendWith(MockitoExtension.class)
class SyncCapturingLdapInterfaceTest {

    private static final UUID DIR = UUID.randomUUID();
    private static final String OLD = "uid=alice,ou=people,dc=src";
    private static final String NEW_SUPERIOR = "ou=archive,dc=src";

    @Mock private FullLDAPInterface delegate;
    @Mock private SyncWriteCaptor captor;

    @Test
    void modifyDn_withNewSuperior_capturesOldAndNewDn() throws Exception {
        when(delegate.modifyDN(anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new LDAPResult(1, ResultCode.SUCCESS));
        SyncCapturingLdapInterface iface = new SyncCapturingLdapInterface(delegate, captor, DIR);

        iface.modifyDN(OLD, "uid=alice", true, NEW_SUPERIOR);

        InOrder order = inOrder(captor);
        order.verify(captor).onWrite(DIR, OLD);
        order.verify(captor).onWrite(DIR, "uid=alice," + NEW_SUPERIOR);
        verifyNoMoreInteractions(captor);
    }

    @Test
    void modifyDn_request_capturesOldAndNewDn() throws Exception {
        when(delegate.modifyDN(any(ModifyDNRequest.class))).thenReturn(new LDAPResult(1, ResultCode.SUCCESS));
        SyncCapturingLdapInterface iface = new SyncCapturingLdapInterface(delegate, captor, DIR);

        iface.modifyDN(new ModifyDNRequest(OLD, "uid=aadams", true));

        verify(captor).onWrite(DIR, OLD);
        verify(captor).onWrite(DIR, "uid=aadams,ou=people,dc=src");
        verifyNoMoreInteractions(captor);
    }

    @Test
    void modifyDn_failure_capturesNothing() throws Exception {
        when(delegate.modifyDN(anyString(), anyString(), anyBoolean()))
                .thenReturn(new LDAPResult(1, ResultCode.INSUFFICIENT_ACCESS_RIGHTS));
        SyncCapturingLdapInterface iface = new SyncCapturingLdapInterface(delegate, captor, DIR);

        iface.modifyDN(OLD, "uid=aadams", true);

        verify(captor, never()).onWrite(any(), anyString());
    }
}
