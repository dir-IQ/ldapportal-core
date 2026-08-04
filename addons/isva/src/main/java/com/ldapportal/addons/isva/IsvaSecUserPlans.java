// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.core.provisioning.AddStep;
import com.ldapportal.core.provisioning.DeleteStep;
import com.ldapportal.core.provisioning.ModifyStep;
import com.ldapportal.core.provisioning.StepFailurePolicy;
import com.ldapportal.core.provisioning.UserCreatePayload;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The discrete, independently-invocable building blocks for the ISVA
 * account — i.e. the {@code secUser} identity — extracted from
 * {@link IsvaProvisioningInterceptor} so they have one shared home.
 *
 * <p>The interceptor's user-lifecycle paths (create / delete) compose
 * the grant / revoke fragments; the account-management feature
 * ({@code IsvaAccountService}) and the integrity-reconciliation feature
 * compose the same fragments — plus the account verbs ({@link #suspend},
 * {@link #restore}, {@link #renew}, {@link #forceCredentialReset},
 * {@link #grantInlineOnExisting}) — to act on the secUser side of an
 * <em>existing</em> identity. Every method is a pure plan-fragment
 * builder: no I/O, no LDAP, no state.</p>
 *
 * <p>This is the operation the
 * {@code EntitlementProvider.apply(grant | revoke VENDOR_ACCOUNT)}
 * north-star points at — keeping the fragments here lets that provider
 * reuse them without reshaping.</p>
 */
@Component
public class IsvaSecUserPlans {

    // ── grant ────────────────────────────────────────────────────────

    /**
     * INLINE-mode grant for a <em>new</em> entry: fold the {@code secUser}
     * objectClass and the {@code sec*} overlay onto the demographic
     * attributes (inline mode keeps a single entry carrying both).
     */
    public List<Attribute> grantInline(List<Attribute> demographicAttrs,
                                       VendorIntegrationIsvaConfig cfg,
                                       UserCreatePayload payload) {
        List<Attribute> attrs = augmentObjectClass(demographicAttrs, secUserObjectClasses(cfg));
        String uid = firstValueOrEmpty(payload.attributes().get("uid"));
        for (Map.Entry<String, String> e : secDefaults(cfg, uid).entrySet()) {
            addIfAbsent(attrs, e.getKey(), e.getValue());
        }
        return attrs;
    }

    /**
     * INLINE-mode grant onto an <em>existing</em> demographic entry: the
     * MODIFY that adds the {@code secUser} objectClass + {@code sec*}
     * defaults. The one fragment with no lifecycle-path equivalent (the
     * create path folds these into a fresh ADD instead). Used to grant an
     * ISVA account to a demographic user that lacks one.
     */
    public ModifyStep grantInlineOnExisting(String demographicDn,
                                            VendorIntegrationIsvaConfig cfg,
                                            String uid) {
        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification(ModificationType.ADD, "objectClass",
                secUserObjectClasses(cfg).toArray(new String[0])));
        for (Map.Entry<String, String> e : secDefaults(cfg, uid).entrySet()) {
            mods.add(new Modification(ModificationType.ADD, e.getKey(), e.getValue()));
        }
        return ModifyStep.of(demographicDn, mods);
    }

    /**
     * LINKED-mode grant: the ADD step that creates the paired secUser
     * entry under the management DIT. {@link StepFailurePolicy#COMPENSATE}
     * so a failure rolls back the just-created demographic entry.
     */
    public AddStep grantLinked(VendorIntegrationIsvaConfig cfg, UserCreatePayload payload) {
        String secUserDn = computeSecUserDn(cfg, payload);
        List<Attribute> secUserAttrs = buildSecUserOnlyAttributes(cfg, payload, secUserDn);
        return new AddStep(secUserDn, secUserAttrs, StepFailurePolicy.COMPENSATE);
    }

    /**
     * The {@code sec*} overlay attributes written by every grant path —
     * single source of truth, also used by {@link #revokeInlineOnExisting}
     * to know which attributes to strip on an inline-mode hard revoke.
     * Order matches {@link #secDefaults} so the two stay in lockstep when
     * the schema grows.
     */
    static final List<String> SEC_OVERLAY_ATTRS = List.of(
            "secLogin",
            "secLoginType",
            "secAuthority",
            "secAcctValid",
            "secPwdValid",
            "secValidUntil",
            "secPwdLastChanged");

    // ── revoke ───────────────────────────────────────────────────────

    /**
     * Revoke (soft-disable) the ISVA account: the MODIFY marking the
     * secUser invalid ({@code secAcctValid=FALSE} + {@code secValidUntil=now}).
     * Targets the demographic DN in inline mode, the paired secUser DN
     * in linked mode.
     */
    public ModifyStep disable(String secUserDn) {
        List<Modification> mods = List.of(
                new Modification(ModificationType.REPLACE, "secAcctValid", "FALSE"),
                new Modification(ModificationType.REPLACE, "secValidUntil",
                        generalizedTime(Instant.now())));
        return ModifyStep.of(secUserDn, mods);
    }

    /**
     * Revoke (hard) the ISVA account: DEL the paired secUser entry
     * (linked mode only — inline's hard delete removes the single
     * entry via the baseline DEL).
     */
    public DeleteStep hardDelete(String secUserDn) {
        return DeleteStep.of(secUserDn);
    }

    /**
     * INLINE-mode hard revoke: strip the {@code secUser} objectClass
     * and the {@code sec*} overlay from a demographic entry, leaving
     * the underlying identity intact. Mirror of
     * {@link #grantInlineOnExisting}. The account-management feature's
     * "hard revoke" verb uses this in inline mode; linked mode uses
     * {@link #hardDelete} against the paired secUser DN instead.
     *
     * <p>Only attributes in {@link #SEC_OVERLAY_ATTRS} are stripped.
     * If a deployment has set other {@code sec*} attributes
     * out-of-band, those stay; the orphaned overlay attributes are
     * inert without the {@code secUser} objectClass.</p>
     *
     * <p>The configured overlay objectClasses (same set
     * {@link #grantInlineOnExisting} added) are removed too, so the
     * teardown mirrors the grant. If the configured class set changed
     * between grant and revoke, the DELETE targets the current set —
     * the same drift caveat that applies to the {@code sec*} attrs.</p>
     */
    public ModifyStep revokeInlineOnExisting(String demographicDn,
                                             VendorIntegrationIsvaConfig cfg) {
        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification(ModificationType.DELETE, "objectClass",
                secUserObjectClasses(cfg).toArray(new String[0])));
        for (String attr : SEC_OVERLAY_ATTRS) {
            mods.add(new Modification(ModificationType.DELETE, attr));
        }
        return ModifyStep.of(demographicDn, mods);
    }

    // ── account verbs (act on an existing identity) ──────────────────

    /**
     * Suspend: flip {@code secAcctValid=FALSE} only, leaving
     * {@code secValidUntil} untouched so {@link #restore} is a clean
     * inverse. (Contrast {@link #disable}, which also expires the
     * account — that's the lifecycle-delete semantic.)
     */
    public ModifyStep suspend(String dn) {
        return ModifyStep.of(dn, List.of(
                new Modification(ModificationType.REPLACE, "secAcctValid", "FALSE")));
    }

    /** Restore: flip {@code secAcctValid=TRUE}. Inverse of {@link #suspend}. */
    public ModifyStep restore(String dn) {
        return ModifyStep.of(dn, List.of(
                new Modification(ModificationType.REPLACE, "secAcctValid", "TRUE")));
    }

    /** Renew: extend {@code secValidUntil} to the supplied instant. */
    public ModifyStep renew(String dn, Instant validUntil) {
        return ModifyStep.of(dn, List.of(
                new Modification(ModificationType.REPLACE, "secValidUntil",
                        generalizedTime(validUntil))));
    }

    /** Force credential reset: invalidate {@code secPwdValid}. */
    public ModifyStep forceCredentialReset(String dn) {
        return ModifyStep.of(dn, List.of(
                new Modification(ModificationType.REPLACE, "secPwdValid", "FALSE")));
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Build the secUser entry's DN under the management DIT. The RDN
     * attribute name is free-form ({@code cfg.secuserRdnAttribute},
     * default {@code secUUID}); the RDN value is independently sourced
     * via {@code cfg.secuserRdnValueSource} — a generated UUID, or the
     * user's {@code uid}. Decoupling the two lets a non-stock attribute
     * (e.g. {@code principalName}) pair with the {@code uid} value.
     */
    private String computeSecUserDn(VendorIntegrationIsvaConfig cfg, UserCreatePayload payload) {
        String rdnAttr = cfg.getSecuserRdnAttribute();
        if (rdnAttr == null || rdnAttr.isBlank()) {
            rdnAttr = "secUUID";
        }
        IsvaRdnValueSource source = cfg.getSecuserRdnValueSource() != null
                ? cfg.getSecuserRdnValueSource()
                : IsvaRdnValueSource.GENERATED_UUID;
        String rdnValue = switch (source) {
            case GENERATED_UUID -> UUID.randomUUID().toString();
            case UID -> firstValueOrEmpty(payload.attributes().get("uid"));
        };
        return rdnAttr + "=" + rdnValue + "," + cfg.getManagementDitBaseDn();
    }

    /**
     * The objectClass set defining the secUser identity, from config.
     * {@code secUser} is always present (the lookup / probe filters key
     * on it); extras (e.g. {@code eUser}) bring in additional naming
     * attributes. Defaults to {@code [secUser]} when unset. {@code top}
     * is the caller's concern — added for the fresh linked ADD, omitted
     * for the inline overlay onto an entry that already has it.
     */
    private static List<String> secUserObjectClasses(VendorIntegrationIsvaConfig cfg) {
        // LinkedHashSet (case-insensitive on secUser) keeps configured
        // order while guaranteeing secUser is present exactly once.
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        boolean hasSecUser = false;
        List<String> configured = cfg.getSecuserObjectClasses();
        if (configured != null) {
            for (String oc : configured) {
                if (oc == null || oc.isBlank()) {
                    continue;
                }
                classes.add(oc);
                if ("secUser".equalsIgnoreCase(oc)) {
                    hasSecUser = true;
                }
            }
        }
        if (!hasSecUser) {
            classes.add("secUser");
        }
        return new ArrayList<>(classes);
    }

    /**
     * Attributes that go on the secUser entry in linked mode —
     * just the ISVA-specific overlay plus the secDN back-reference.
     * No demographic attributes; those stay on the demographic
     * entry alone.
     */
    private List<Attribute> buildSecUserOnlyAttributes(VendorIntegrationIsvaConfig cfg,
                                                       UserCreatePayload payload,
                                                       String secUserDn) {
        List<Attribute> attrs = new ArrayList<>();
        // top (structural root for the fresh ADD) + the configured
        // overlay classes (secUser always present, plus any extras
        // such as eUser that define the chosen RDN attribute).
        List<String> objectClasses = new ArrayList<>();
        objectClasses.add("top");
        objectClasses.addAll(secUserObjectClasses(cfg));
        attrs.add(new Attribute("objectClass", objectClasses.toArray(new String[0])));
        // Mirror the RDN attribute as a value on the entry too —
        // LDAP requires the RDN's attribute to be present on the
        // entry.
        String rdnPair = secUserDn.substring(0, secUserDn.indexOf(','));
        String rdnAttr = rdnPair.substring(0, rdnPair.indexOf('='));
        String rdnValue = rdnPair.substring(rdnPair.indexOf('=') + 1);
        attrs.add(new Attribute(rdnAttr, rdnValue));
        // The secDN back-reference is the key piece linking this
        // entry to its demographic counterpart. Without it ISVA
        // can't authenticate via the secUser path.
        attrs.add(new Attribute("secDN", payload.dn()));
        String uid = firstValueOrEmpty(payload.attributes().get("uid"));
        for (Map.Entry<String, String> e : secDefaults(cfg, uid).entrySet()) {
            addIfAbsent(attrs, e.getKey(), e.getValue());
        }
        return attrs;
    }

    /**
     * The standard {@code sec*} attribute defaults, in stable order.
     * Single source of truth for the values written by every grant
     * path (inline-new, inline-on-existing, linked) — the only
     * difference between paths is whether they land as ADD-plan
     * attributes or MODIFY-ADD modifications.
     *
     * <p>The timestamps ({@code secValidUntil}, {@code secPwdLastChanged})
     * are computed per call from {@code Instant.now()}.</p>
     */
    private Map<String, String> secDefaults(VendorIntegrationIsvaConfig cfg, String uid) {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("secLogin", uid);
        // secLoginType is a MUST attribute of IBM's stock secUser
        // objectClass (alongside secAuthority); without it the very first
        // grant fails with an object-class-violation. Deployment-varying,
        // so it's configurable like secAuthority, falling back to the
        // vanilla-install "Default".
        defaults.put("secLoginType", nonNull(cfg.getSecLoginType(), "Default"));
        defaults.put("secAuthority", nonNull(cfg.getSecAuthority(), "Default"));
        defaults.put("secAcctValid", "TRUE");
        defaults.put("secPwdValid", "TRUE");
        defaults.put("secValidUntil", generalizedTime(Instant.now().plusSeconds(
                yearsInSeconds(cfg.getDefaultValidUntilYears()))));
        defaults.put("secPwdLastChanged", generalizedTime(Instant.now()));
        return defaults;
    }

    private static void addIfAbsent(List<Attribute> attrs, String name, String value) {
        for (Attribute a : attrs) {
            if (a.getName().equalsIgnoreCase(name)) {
                return; // caller already supplied this attribute (e.g. via profile default)
            }
        }
        attrs.add(new Attribute(name, value));
    }

    private static List<Attribute> augmentObjectClass(List<Attribute> attrs,
                                                       List<String> extraObjectClasses) {
        List<Attribute> out = new ArrayList<>(attrs.size() + 1);
        boolean found = false;
        for (Attribute attr : attrs) {
            if ("objectClass".equalsIgnoreCase(attr.getName())) {
                // Idempotent: append only the configured classes the
                // entry doesn't already carry (typical when the
                // profile's objectClassNames already include secUser),
                // preserving existing values and order.
                LinkedHashSet<String> merged = new LinkedHashSet<>();
                LinkedHashSet<String> lower = new LinkedHashSet<>();
                for (String v : attr.getValues()) {
                    merged.add(v);
                    lower.add(v.toLowerCase());
                }
                for (String extra : extraObjectClasses) {
                    if (!lower.contains(extra.toLowerCase())) {
                        merged.add(extra);
                        lower.add(extra.toLowerCase());
                    }
                }
                out.add(new Attribute("objectClass", merged.toArray(new String[0])));
                found = true;
            } else {
                out.add(attr);
            }
        }
        if (!found) {
            out.add(new Attribute("objectClass", extraObjectClasses.toArray(new String[0])));
        }
        return out;
    }

    private static String firstValueOrEmpty(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    private static String nonNull(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long yearsInSeconds(int years) {
        return Math.round(years * 365.25d * 24d * 3600d);
    }

    /**
     * LDAP generalized-time format ({@code yyyyMMddHHmmss'Z'}, UTC).
     * Public + static so the interceptor's password-set paths — which
     * stamp {@code secPwdLastChanged} but aren't account verbs — share
     * the one formatter rather than duplicating it.
     */
    public static String generalizedTime(Instant t) {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(t);
    }
}
