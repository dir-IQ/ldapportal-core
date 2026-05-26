// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.dto.ProbeResult;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the diagnostic checks behind
 * {@code POST /isva-config/probe}. v1 covers the two checks an
 * operator actually needs at config-save time:
 *
 * <ol>
 *   <li><b>Reachability</b> — can the configured
 *       {@code management_dit_base_dn} be read with the directory
 *       connection's bind credentials? Catches typos and
 *       permission misconfigurations early.</li>
 *   <li><b>Sample secUser presence</b> — does at least one entry
 *       with {@code objectClass: secUser} exist under that base?
 *       False on a fresh install before any user has been
 *       created; true once the operator runs through the wizard
 *       once. Useful confirmation that the configured DIT is the
 *       right one.</li>
 * </ol>
 *
 * <p>Inline mode doesn't have a management DIT, so the probe is
 * vacuously OK there — both flags true, no warnings. The UI may
 * choose not to render the probe button in inline mode.</p>
 *
 * <p>Deliberately omitted from v1: schema-attribute-type check
 * (needs cn=subschema parsing — non-trivial), group-member-target
 * sampler (requires reading a group entry — operator can spot-
 * check via the directory browser). These join the probe in
 * v1.1 once a real customer asks.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IsvaConfigProbeService {

    private final LdapConnectionFactory connectionFactory;

    public ProbeResult probe(DirectoryConnection dir, VendorIntegrationIsvaConfig cfg) {
        if (cfg.getTopologyMode() == IsvaTopologyMode.INLINE) {
            // Inline mode: no separate DIT to check. The user
            // entries themselves carry sec* attrs; nothing
            // dedicated to probe.
            return new ProbeResult(true, true,
                    List.of("Inline mode — no management DIT to probe."));
        }

        String managementDit = cfg.getManagementDitBaseDn();
        if (managementDit == null || managementDit.isBlank()) {
            // Defensive — DB CHECK constraint should catch this,
            // but a clear probe failure beats a 500.
            return new ProbeResult(false, false,
                    List.of("Linked mode is configured but management_dit_base_dn is empty."));
        }

        List<String> warnings = new ArrayList<>();
        boolean reachable = false;
        boolean sampleFound = false;

        try {
            reachable = checkReachable(dir, managementDit);
            if (reachable) {
                sampleFound = sampleSecUserExists(dir, managementDit, warnings);
                if (!sampleFound) {
                    warnings.add("No `secUser` entries found under " + managementDit
                            + " yet. This is normal on a fresh install — provision "
                            + "a user via the wizard, then re-run the probe.");
                }
            } else {
                warnings.add("Could not read " + managementDit + " — check the bind "
                        + "DN's permissions and that the DIT exists in this directory.");
            }
        } catch (Exception e) {
            // Surface as a warning rather than a 500 so the operator
            // sees the actual underlying error in the panel.
            warnings.add("Probe error: " + e.getMessage());
            log.warn("ISVA probe failed for directory {}: {}",
                    dir.getDisplayName(), e.getMessage(), e);
        }

        return new ProbeResult(reachable, sampleFound, warnings);
    }

    /**
     * Read the base entry of the management DIT. Returns true if
     * the entry exists and the bind DN can read it.
     */
    private boolean checkReachable(DirectoryConnection dir, String baseDn) {
        return connectionFactory.withConnection(dir, conn -> {
            try {
                return conn.getEntry(baseDn) != null;
            } catch (LDAPException e) {
                log.debug("Management DIT base {} unreachable: {}",
                        baseDn, e.getMessage());
                return false;
            }
        });
    }

    /**
     * Search for at least one secUser entry under the base. We cap
     * the search at 1 result — "exists" is the question; "how
     * many" isn't part of the probe contract.
     */
    private boolean sampleSecUserExists(DirectoryConnection dir,
                                          String baseDn,
                                          List<String> warnings) {
        return connectionFactory.withConnection(dir, conn -> {
            try {
                SearchRequest req = new SearchRequest(
                        baseDn,
                        SearchScope.SUB,
                        Filter.createEqualityFilter("objectClass", "secUser"),
                        "1.1");
                req.setSizeLimit(1);
                SearchResult result = conn.search(req);
                return result.getEntryCount() > 0;
            } catch (LDAPException e) {
                warnings.add("secUser sample search errored: " + e.getMessage());
                return false;
            }
        });
    }
}
