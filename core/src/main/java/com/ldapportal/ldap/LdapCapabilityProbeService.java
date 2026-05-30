// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.model.DirectoryCapabilities;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RootDSE;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the root DSE on a directory connection and returns a
 * {@link DirectoryCapabilities} snapshot. Invoked out-of-band from
 * {@link DirectoryCapabilityRefresher}, which handles AFTER_COMMIT
 * dispatch so the probe never sits inside the save transaction.
 *
 * <p>Returns {@code null} (signalling "no useful snapshot") in three
 * cases the UI handles by hiding the vendor chip:
 * <ul>
 *   <li>The connection is Entra ID — not LDAP, no root DSE to read.</li>
 *   <li>The server returned no root DSE entry — some hardened
 *       deployments hide it. Returning {@code null} (rather than a
 *       non-null snapshot with vendor=null) keeps the UI fallback
 *       from pretending we confirmed the vendor when we confirmed
 *       only that the bind succeeded.</li>
 *   <li>Any exception during the LDAP call — connection broken, RBAC
 *       denial, etc. Logged and swallowed; capability persistence is
 *       best-effort.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LdapCapabilityProbeService {

    private final LdapConnectionFactory connectionFactory;

    /**
     * Probe through the cached connection pool. The connection pool is
     * created on demand if not already cached — fine here because the
     * caller is the AFTER_COMMIT refresher, so the directory row exists
     * by the time we open the pool (no risk of a rolled-back UUID
     * orphaning sockets in the cache).
     */
    public DirectoryCapabilities probe(DirectoryConnection dc) {
        if (dc == null || dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            return null;
        }
        try {
            return connectionFactory.withConnection(dc, this::readRootDse);
        } catch (Exception ex) {
            log.warn("Capability probe failed for directory {}: {}",
                    dc.getDisplayName(), ex.getMessage());
            return null;
        }
    }

    private DirectoryCapabilities readRootDse(LDAPConnection conn) throws LDAPException {
        RootDSE root = conn.getRootDSE();
        if (root == null) {
            // Returning null instead of an empty snapshot is deliberate:
            // a non-null snapshot with vendor=null would trick the UI's
            // type-label fallback into rendering a confident vendor chip
            // ("Oracle Unified Directory", say) whose only evidence is
            // the operator's own dropdown pick — confirming a guess
            // they made themselves. With null, the chip simply doesn't
            // render and the absence is itself the signal.
            log.debug("Server returned no root DSE — capability snapshot omitted");
            return null;
        }
        return new DirectoryCapabilities(
                root.getAttributeValue("vendorName"),
                root.getAttributeValue("vendorVersion"),
                asList(root.getSupportedControlOIDs()),
                asList(root.getSupportedExtendedOperationOIDs()),
                asList(root.getSupportedSASLMechanismNames()),
                asList(root.getNamingContextDNs()),
                OffsetDateTime.now());
    }

    private static List<String> asList(String[] arr) {
        return arr == null ? List.of() : Arrays.stream(arr).sorted().toList();
    }
}
