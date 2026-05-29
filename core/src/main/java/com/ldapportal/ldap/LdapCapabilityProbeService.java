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
 * {@link DirectoryCapabilities} snapshot. Called once at connect-time
 * (on Test Connection and on save) by
 * {@link com.ldapportal.service.DirectoryConnectionService}; the
 * result is persisted as JSONB on
 * {@link DirectoryConnection#getCapabilities()}.
 *
 * <p>Two flavours of failure are explicitly tolerated:
 * <ul>
 *   <li><b>Entra ID</b> — not an LDAP server, no root DSE. The probe
 *       short-circuits to {@code null} for Entra-type connections so
 *       callers don't have to guard at every call site.</li>
 *   <li><b>Server returns no root DSE entry</b> — some hardened
 *       deployments hide it from unauthenticated callers; the probe
 *       runs under the configured bind, so this is rare but possible
 *       on aggressively-locked-down servers. We return {@code null}
 *       rather than throwing, so a partial-config directory still saves
 *       successfully.</li>
 * </ul>
 * Errors inside the LDAP call itself (broken connection, RBAC denial)
 * propagate normally — those are real connection problems that the
 * operator should see during Test Connection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LdapCapabilityProbeService {

    private final LdapConnectionFactory connectionFactory;

    /**
     * Probe through the cached connection pool. Used during
     * {@code updateDirectory} / {@code createDirectory} once the
     * connection is persisted; the pool exists because save already
     * ran.
     */
    public DirectoryCapabilities probe(DirectoryConnection dc) {
        if (dc == null || dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            return null;
        }
        try {
            return connectionFactory.withConnection(dc, this::readRootDse);
        } catch (Exception ex) {
            // Capability probing is non-fatal — log and move on. A
            // missing capabilities row just means the badge isn't
            // displayed; the directory still works for CRUD.
            log.warn("Capability probe failed for directory {}: {}",
                    dc.getDisplayName(), ex.getMessage());
            return null;
        }
    }

    /**
     * Probe over a one-shot connection — for use during Test Connection,
     * where the directory hasn't been saved yet and there's no pool to
     * draw from.
     */
    public DirectoryCapabilities probe(LDAPConnection conn) {
        try {
            return readRootDse(conn);
        } catch (Exception ex) {
            log.warn("Capability probe over one-shot connection failed: {}",
                    ex.getMessage());
            return null;
        }
    }

    private DirectoryCapabilities readRootDse(LDAPConnection conn) throws LDAPException {
        RootDSE root = conn.getRootDSE();
        if (root == null) {
            log.debug("Server returned no root DSE — capability snapshot will be empty");
            return new DirectoryCapabilities(
                    null, null, List.of(), List.of(), List.of(), List.of(),
                    OffsetDateTime.now());
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
