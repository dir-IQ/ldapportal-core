// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.dto.ProbeResult;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * <p>A third check — <b>schema validation</b> — runs in <em>both</em>
 * modes: every configured secUser objectClass must exist in the
 * server's published schema, and (linked mode) the configured RDN
 * attribute must be permitted by one of those classes. This catches a
 * mis-set RDN/objectClass combination (e.g. RDN {@code principalName}
 * without the {@code eUser} class that defines it) before the operator
 * enables provisioning, where it would otherwise surface as a
 * server-side ADD rejection per user.</p>
 *
 * <p>Inline mode has no management DIT, so reachability + sample are
 * vacuously OK there — but the schema check still applies, since the
 * configured objectClasses overlay onto the demographic entry.</p>
 *
 * <p>Deliberately omitted: group-member-target sampler (requires
 * reading a group entry — operator can spot-check via the directory
 * browser). Joins the probe once a real customer asks.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IsvaConfigProbeService {

    private final LdapConnectionFactory connectionFactory;

    public ProbeResult probe(DirectoryConnection dir, VendorIntegrationIsvaConfig cfg) {
        List<String> warnings = new ArrayList<>();

        // Schema validation runs in both modes — the configured secUser
        // objectClasses apply to inline overlays and linked entries
        // alike, and only needs a connection (not a management DIT).
        Boolean schemaValid = validateSchema(dir, cfg, warnings);

        if (cfg.getTopologyMode() == IsvaTopologyMode.INLINE) {
            // Inline mode: no separate DIT to check. The user
            // entries themselves carry sec* attrs; nothing
            // dedicated to probe beyond the schema check above.
            warnings.add("Inline mode — no management DIT to probe.");
            return new ProbeResult(true, true, schemaValid, warnings);
        }

        String managementDit = cfg.getManagementDitBaseDn();
        if (managementDit == null || managementDit.isBlank()) {
            // Defensive — DB CHECK constraint should catch this,
            // but a clear probe failure beats a 500.
            warnings.add("Linked mode is configured but management_dit_base_dn is empty.");
            return new ProbeResult(false, false, schemaValid, warnings);
        }

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

        return new ProbeResult(reachable, sampleFound, schemaValid, warnings);
    }

    /**
     * Validate the configured secUser objectClasses (and, in linked
     * mode, the RDN attribute) against the server's published schema.
     *
     * <ul>
     *   <li>Every configured objectClass must be defined in the
     *       server schema.</li>
     *   <li>Linked mode only: the configured RDN attribute must be a
     *       MUST or MAY of at least one configured objectClass (with
     *       superior-class resolution) — otherwise the secUser ADD
     *       would be rejected by the server at provisioning time.</li>
     * </ul>
     *
     * @return {@code TRUE} when all checks pass, {@code FALSE} when at
     *         least one fails, {@code null} when the server schema
     *         couldn't be read to decide (warnings explain which).
     */
    private Boolean validateSchema(DirectoryConnection dir,
                                   VendorIntegrationIsvaConfig cfg,
                                   List<String> warnings) {
        List<String> objectClasses = cfg.getSecuserObjectClasses();
        if (objectClasses == null || objectClasses.isEmpty()) {
            // Should never happen post-normalization, but treat an empty
            // set as nothing to validate rather than erroring.
            objectClasses = List.of("secUser");
        }

        final List<String> classesToCheck = objectClasses;
        try {
            return connectionFactory.withConnection(dir, conn -> {
                Schema schema;
                try {
                    schema = conn.getSchema();
                } catch (LDAPException e) {
                    schema = null;
                }
                if (schema == null) {
                    warnings.add("Could not read the server schema to validate the "
                            + "configured secUser objectClasses / RDN attribute. The "
                            + "directory may restrict subschema access to the bind DN.");
                    return null;
                }

                boolean valid = true;

                // 1. Each configured objectClass must exist in the schema.
                //    Accumulate the union of permitted attributes (MUST +
                //    MAY, superior classes resolved) for the RDN check.
                Set<String> permittedAttrs = new LinkedHashSet<>();
                for (String ocName : classesToCheck) {
                    ObjectClassDefinition oc = schema.getObjectClass(ocName);
                    if (oc == null) {
                        warnings.add("Configured secUser objectClass `" + ocName
                                + "` is not defined in the server schema.");
                        valid = false;
                        continue;
                    }
                    for (AttributeTypeDefinition at : oc.getRequiredAttributes(schema, true)) {
                        addAttributeNames(permittedAttrs, at);
                    }
                    for (AttributeTypeDefinition at : oc.getOptionalAttributes(schema, true)) {
                        addAttributeNames(permittedAttrs, at);
                    }
                }

                // 2. Linked mode: the RDN attribute must be defined and
                //    permitted by one of the configured classes.
                if (cfg.getTopologyMode() == IsvaTopologyMode.LINKED) {
                    String rdnAttr = cfg.getSecuserRdnAttribute();
                    if (rdnAttr == null || rdnAttr.isBlank()) {
                        rdnAttr = "secUUID";
                    }
                    if (schema.getAttributeType(rdnAttr) == null) {
                        warnings.add("Configured RDN attribute `" + rdnAttr
                                + "` is not defined in the server schema.");
                        valid = false;
                    } else if (!permittedAttrs.contains(rdnAttr.toLowerCase())) {
                        warnings.add("RDN attribute `" + rdnAttr + "` is not permitted by any "
                                + "configured objectClass (" + String.join(", ", classesToCheck)
                                + "). Add the objectClass that defines it (e.g. `eUser` for "
                                + "`principalName`), or change the RDN attribute.");
                        valid = false;
                    }
                }

                return valid;
            });
        } catch (Exception e) {
            warnings.add("Schema validation error: " + e.getMessage());
            log.warn("ISVA schema probe failed for directory {}: {}",
                    dir.getDisplayName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Record every name an attribute type goes by (it can have several
     * aliases, e.g. {@code cn} / {@code commonName}), lower-cased, so the
     * RDN membership test matches regardless of which spelling the
     * operator configured.
     */
    private static void addAttributeNames(Set<String> into, AttributeTypeDefinition at) {
        for (String name : at.getNames()) {
            into.add(name.toLowerCase());
        }
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
     *
     * <p>The cap is the subtlety: a directory with <em>more than
     * one</em> secUser answers a {@code sizeLimit=1} search by
     * returning the first entry and then a
     * {@link ResultCode#SIZE_LIMIT_EXCEEDED} result code, which the
     * UnboundID SDK surfaces as an {@link LDAPException}. That is
     * <b>proof the entry exists</b>, not a failure — so we treat
     * SIZE_LIMIT_EXCEEDED as "found". (Before this was handled, any
     * directory that actually had users — the normal case — reported
     * "no sample secUser found", the exact opposite of the truth.)</p>
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
                if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED) {
                    // Hit the cap → at least one secUser is present.
                    return true;
                }
                warnings.add("secUser sample search errored: " + e.getMessage());
                return false;
            }
        });
    }
}
