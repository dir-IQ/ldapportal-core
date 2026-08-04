// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     * The <em>optional</em> {@code sec*} overlay attributes a grant may
     * write, in stable order. These are configurable per directory via
     * {@code cfg.secuserOverlayAttributes}: a deployment whose
     * {@code secUser} schema omits some of them (e.g. a registry with no
     * {@code secValidUntil} expiry model, or one that doesn't carry
     * {@code secLogin}) trims the set so provisioning doesn't fail with
     * "attribute X not allowed by objectClass secUser".
     *
     * <p>{@code secLoginType} and {@code secAuthority} are deliberately
     * <em>not</em> here — IBM's stock {@code secUser} lists them as MUST,
     * so they're always written (their <em>values</em> are configured
     * separately). This list governs only the optional overlay.</p>
     */
    public static final List<String> OPTIONAL_OVERLAY_ATTRS = List.of(
            "secLogin",
            "secAcctValid",
            "secPwdValid",
            "secValidUntil",
            "secPwdLastChanged");

    /**
     * The optional overlay attributes actually enabled for this config —
     * the intersection of {@link #OPTIONAL_OVERLAY_ATTRS} with
     * {@code cfg.secuserOverlayAttributes} (case-insensitive, canonical
     * spelling + order preserved). A {@code null} configured list means
     * "unset" and resolves to the full set (preserves legacy behaviour);
     * an explicitly empty list writes none of the optional attributes.
     */
    public static List<String> enabledOverlayAttrs(VendorIntegrationIsvaConfig cfg) {
        List<String> configured = cfg.getSecuserOverlayAttributes();
        if (configured == null) {
            return OPTIONAL_OVERLAY_ATTRS;
        }
        Set<String> want = new LinkedHashSet<>();
        for (String a : configured) {
            if (a != null && !a.isBlank()) {
                want.add(a.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> out = new ArrayList<>();
        for (String canonical : OPTIONAL_OVERLAY_ATTRS) {
            if (want.contains(canonical.toLowerCase(Locale.ROOT))) {
                out.add(canonical);
            }
        }
        return out;
    }

    /** Whether a given optional overlay attribute is enabled for this config. */
    public static boolean overlayEnabled(VendorIntegrationIsvaConfig cfg, String attr) {
        for (String a : enabledOverlayAttrs(cfg)) {
            if (a.equalsIgnoreCase(attr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The full ordered list of {@code sec*} attribute NAMES a grant
     * writes for this config: the two always-on MUST attrs
     * ({@code secLoginType}, {@code secAuthority}) plus each enabled
     * optional attr. Single source of truth shared by {@link #secDefaults}
     * (what to write) and {@link #revokeInlineOnExisting} (what to strip),
     * so the two can never drift.
     */
    public static List<String> writtenOverlayAttrNames(VendorIntegrationIsvaConfig cfg) {
        Set<String> on = new LinkedHashSet<>();
        for (String a : enabledOverlayAttrs(cfg)) {
            on.add(a.toLowerCase(Locale.ROOT));
        }
        List<String> names = new ArrayList<>();
        if (on.contains("seclogin")) {
            names.add("secLogin");
        }
        names.add("secLoginType");
        names.add("secAuthority");
        if (on.contains("secacctvalid")) {
            names.add("secAcctValid");
        }
        if (on.contains("secpwdvalid")) {
            names.add("secPwdValid");
        }
        if (on.contains("secvaliduntil")) {
            names.add("secValidUntil");
        }
        if (on.contains("secpwdlastchanged")) {
            names.add("secPwdLastChanged");
        }
        return names;
    }

    /**
     * Every attribute NAME a grant writes onto the {@code secUser}
     * identity for this config — the overlay attrs from
     * {@link #writtenOverlayAttrNames} plus, in linked mode, the
     * {@code secDN} back-reference and the configured RDN attribute.
     * {@code objectClass} is implicit and always permitted, so it's
     * excluded. Used by the probe to compare what the app would write
     * against the target server's {@code secUser} schema.
     */
    public static List<String> writtenAttributeNames(VendorIntegrationIsvaConfig cfg) {
        List<String> names = new ArrayList<>(writtenOverlayAttrNames(cfg));
        if (cfg.getTopologyMode() == IsvaTopologyMode.LINKED) {
            names.add("secDN");
            String rdn = cfg.getSecuserRdnAttribute();
            if (rdn == null || rdn.isBlank()) {
                rdn = "secUUID";
            }
            final String rdnAttr = rdn;
            if (names.stream().noneMatch(n -> n.equalsIgnoreCase(rdnAttr))) {
                names.add(rdnAttr);
            }
        }
        return names;
    }

    // ── revoke ───────────────────────────────────────────────────────

    /**
     * Revoke (soft-disable) the ISVA account: the MODIFY marking the
     * secUser invalid ({@code secAcctValid=FALSE}, and — when the
     * directory's overlay includes it — {@code secValidUntil=now}).
     * Targets the demographic DN in inline mode, the paired secUser DN
     * in linked mode.
     *
     * <p>{@code secValidUntil} is written only when it's part of the
     * configured overlay: a deployment whose {@code secUser} schema has
     * no {@code secValidUntil} would otherwise have every soft-delete
     * rejected with "attribute not allowed". There, disable degrades to
     * flipping {@code secAcctValid} alone.</p>
     */
    public ModifyStep disable(String secUserDn, VendorIntegrationIsvaConfig cfg) {
        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification(ModificationType.REPLACE, "secAcctValid", "FALSE"));
        if (overlayEnabled(cfg, "secValidUntil")) {
            mods.add(new Modification(ModificationType.REPLACE, "secValidUntil",
                    generalizedTime(Instant.now())));
        }
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
     * <p>Only the attributes {@link #writtenOverlayAttrNames} reports for
     * this config are stripped — exactly what a grant wrote. If a
     * deployment has set other {@code sec*} attributes out-of-band, those
     * stay; the orphaned overlay attributes are inert without the
     * {@code secUser} objectClass.</p>
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
        for (String attr : writtenOverlayAttrNames(cfg)) {
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
        // writtenOverlayAttrNames is the single source of truth for which
        // attributes a grant emits (the two always-on MUST attrs plus the
        // enabled optional overlay); here we attach each name's value.
        // secLoginType / secAuthority are MUST on IBM's stock secUser, so
        // they always appear; the optional attrs (secLogin, secValidUntil,
        // …) appear only when the directory's configured overlay includes
        // them — a deployment whose secUser schema omits one trims it out
        // rather than failing every grant with "attribute not allowed".
        Map<String, String> defaults = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (String name : writtenOverlayAttrNames(cfg)) {
            defaults.put(name, overlayValue(name, cfg, uid, now));
        }
        return defaults;
    }

    /** The value written for a given overlay attribute name. */
    private String overlayValue(String name, VendorIntegrationIsvaConfig cfg,
                                String uid, Instant now) {
        return switch (name) {
            case "secLogin" -> uid;
            case "secLoginType" -> nonNull(cfg.getSecLoginType(), "Default");
            case "secAuthority" -> nonNull(cfg.getSecAuthority(), "Default");
            case "secAcctValid" -> "TRUE";
            case "secPwdValid" -> "TRUE";
            case "secValidUntil" -> generalizedTime(now.plusSeconds(
                    yearsInSeconds(cfg.getDefaultValidUntilYears())));
            case "secPwdLastChanged" -> generalizedTime(now);
            default -> throw new IllegalStateException("Unknown overlay attribute: " + name);
        };
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
