// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.SecUserAttribute;
import com.ldapportal.addons.isva.entity.SecUserAttributeValueKind;
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
        for (Map.Entry<String, String> e : secDefaults(cfg, payload.attributes()).entrySet()) {
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
                                            Map<String, List<String>> userAttrs) {
        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification(ModificationType.ADD, "objectClass",
                secUserObjectClasses(cfg).toArray(new String[0])));
        for (Map.Entry<String, String> e : secDefaults(cfg, userAttrs).entrySet()) {
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
     * The full set of optional overlay attributes an operator may enable —
     * the {@link #OPTIONAL_OVERLAY_ATTRS default set} plus the IVIA identity
     * attributes that natively-created secUser entries carry but which the
     * app didn't previously write:
     * <ul>
     *   <li>{@code secUUID} — the opaque, immutable per-user identifier. When
     *       it is the RDN attribute it's already written as the RDN value;
     *       enabling it here also writes it (a generated UUID) when the entry
     *       is named on something else (e.g. {@code principalName}).</li>
     *   <li>{@code principalName} — the login/principal (the user's uid).</li>
     *   <li>{@code secDomainId} — {@code <secAuthority>%<principalName>}
     *       (e.g. {@code Default%jdoe}).</li>
     * </ul>
     * These three are <em>opt-in</em> (not in the default set) because a
     * stock {@code secUser} schema need not permit them — enabling them on a
     * schema that doesn't is caught by the Probe. Order is stable so a grant
     * and its inline hard-revoke stay in lockstep.
     */
    public static final List<String> KNOWN_OVERLAY_ATTRS = List.of(
            "secLogin",
            "secAcctValid",
            "secPwdValid",
            "secValidUntil",
            "secPwdLastChanged",
            "secUUID",
            "principalName",
            "secDomainId");

    /**
     * The optional overlay attributes actually enabled for this config —
     * the intersection of {@link #KNOWN_OVERLAY_ATTRS} with
     * {@code cfg.secuserOverlayAttributes} (case-insensitive, canonical
     * spelling + order preserved). A {@code null} configured list means
     * "unset" and resolves to the {@link #OPTIONAL_OVERLAY_ATTRS default
     * set} (preserves legacy behaviour — the identity attrs stay off unless
     * explicitly enabled); an explicitly empty list writes none.
     */
    public static List<String> enabledOverlayAttrs(VendorIntegrationIsvaConfig cfg) {
        // Optional (non-required) attributes enabled in the effective model,
        // in that model's order. secLoginType / secAuthority are the always-on
        // MUST attrs and are excluded here (they're not part of the optional
        // overlay this method reports).
        List<String> out = new ArrayList<>();
        for (SecUserAttribute a : effectiveAttributes(cfg)) {
            if (a.enabled() && !isRequiredAttr(a.name())) {
                out.add(a.name());
            }
        }
        return out;
    }

    /** The legacy optional-overlay set from {@code secuserOverlayAttributes} —
     * the intersection with {@link #KNOWN_OVERLAY_ATTRS} in canonical order,
     * {@code null} → {@link #OPTIONAL_OVERLAY_ATTRS}. Used only by the legacy
     * deriver ({@link #deriveFromLegacy}); reads should go through
     * {@link #enabledOverlayAttrs}. */
    private static List<String> legacyEnabledOptional(VendorIntegrationIsvaConfig cfg) {
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
        for (String canonical : KNOWN_OVERLAY_ATTRS) {
            if (want.contains(canonical.toLowerCase(Locale.ROOT))) {
                out.add(canonical);
            }
        }
        return out;
    }

    /** Whether a given overlay attribute is enabled (written) for this config. */
    public static boolean overlayEnabled(VendorIntegrationIsvaConfig cfg, String attr) {
        for (SecUserAttribute a : effectiveAttributes(cfg)) {
            if (a.enabled() && a.name().equalsIgnoreCase(attr)) {
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
        List<String> names = new ArrayList<>();
        for (SecUserAttribute a : effectiveAttributes(cfg)) {
            if (a.enabled()) {
                names.add(a.name());
            }
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

    // ── unified per-attribute model ──────────────────────────────────

    /** Canonical attribute order — the order a legacy-derived model emits, and
     * the historical order {@code writtenOverlayAttrNames} produced, so a
     * migrated config's ADD lists byte-for-byte match the pre-model output. */
    private static final List<String> CANONICAL_ORDER = List.of(
            "secLogin", "secLoginType", "secAuthority", "secAcctValid",
            "secPwdValid", "secValidUntil", "secPwdLastChanged",
            "secUUID", "principalName", "secDomainId");

    /** The two IBM-{@code secUser}-MUST attributes — always enabled, never
     * excludable. Their <em>values</em> are configurable like any other. */
    private static final Set<String> REQUIRED_ATTRS =
            new LinkedHashSet<>(List.of("secLoginType", "secAuthority"));

    private static boolean isRequiredAttr(String name) {
        for (String r : REQUIRED_ATTRS) {
            if (r.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The effective per-attribute model for this config: the stored
     * {@code secuserAttributes} when set, otherwise an equivalent model
     * {@linkplain #deriveFromLegacy derived} from the legacy value fields.
     * Single source of truth for what a grant writes and how.
     */
    public static List<SecUserAttribute> effectiveAttributes(VendorIntegrationIsvaConfig cfg) {
        List<SecUserAttribute> stored = cfg.getSecuserAttributes();
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        return deriveFromLegacy(cfg);
    }

    /**
     * Build the per-attribute model that reproduces the pre-model behaviour of
     * a config that only has the legacy value fields set — same attributes,
     * same enabled set, same values, same order. Used when
     * {@code secuserAttributes} is null (not migrated) and as the seed the
     * upsert persists on the next save.
     */
    public static List<SecUserAttribute> deriveFromLegacy(VendorIntegrationIsvaConfig cfg) {
        Set<String> optionalOn = new LinkedHashSet<>();
        for (String a : legacyEnabledOptional(cfg)) {
            optionalOn.add(a.toLowerCase(Locale.ROOT));
        }
        String loginType = nonNull(cfg.getSecLoginType(), "Default");
        String authority = nonNull(cfg.getSecAuthority(), "Default");
        int years = cfg.getDefaultValidUntilYears();
        List<SecUserAttribute> out = new ArrayList<>();
        for (String name : CANONICAL_ORDER) {
            boolean enabled = isRequiredAttr(name)
                    || optionalOn.contains(name.toLowerCase(Locale.ROOT));
            out.add(new SecUserAttribute(name, enabled,
                    legacyKind(name), legacyValue(name, loginType, authority, years)));
        }
        return out;
    }

    /**
     * Normalize an operator-supplied model to the canonical full set: every
     * known attribute present exactly once, in canonical order, with the two
     * MUST attrs ({@code secLoginType}, {@code secAuthority}) forced enabled.
     * A supplied row wins for enabled / kind / value; unknown attribute names
     * are dropped; a missing known attribute falls back to a sensible disabled
     * default. This is what the config upsert stores, so a saved model always
     * round-trips to a complete, stable shape the config page can render.
     */
    public static List<SecUserAttribute> normalizeModel(List<SecUserAttribute> requested) {
        Map<String, SecUserAttribute> byName = new LinkedHashMap<>();
        if (requested != null) {
            for (SecUserAttribute a : requested) {
                if (a == null || a.name() == null || a.name().isBlank()) {
                    continue;
                }
                byName.put(a.name().trim().toLowerCase(Locale.ROOT), a);
            }
        }
        List<SecUserAttribute> out = new ArrayList<>();
        for (String name : CANONICAL_ORDER) {
            SecUserAttribute supplied = byName.get(name.toLowerCase(Locale.ROOT));
            if (supplied == null) {
                out.add(defaultAttribute(name));
                continue;
            }
            boolean enabled = isRequiredAttr(name) || supplied.enabled();
            SecUserAttributeValueKind kind = supplied.valueKind() != null
                    ? supplied.valueKind() : legacyKind(name);
            String value = supplied.value() != null
                    ? supplied.value()
                    : legacyValue(name, "Default", "Default", 100);
            out.add(new SecUserAttribute(name, enabled, kind, value));
        }
        return out;
    }

    /** A known attribute's stock default row (disabled unless it's a MUST attr),
     * used to fill a gap when a supplied model omits one. */
    private static SecUserAttribute defaultAttribute(String name) {
        return new SecUserAttribute(name, isRequiredAttr(name),
                legacyKind(name), legacyValue(name, "Default", "Default", 100));
    }

    private static SecUserAttributeValueKind legacyKind(String name) {
        return switch (name) {
            case "secLoginType", "secAuthority", "secAcctValid", "secPwdValid" ->
                    SecUserAttributeValueKind.LITERAL;
            default -> SecUserAttributeValueKind.COMPUTED;
        };
    }

    private static String legacyValue(String name, String loginType,
                                      String authority, int years) {
        return switch (name) {
            case "secLogin" -> "${user.uid}";
            case "secLoginType" -> loginType;
            case "secAuthority" -> authority;
            case "secAcctValid" -> "TRUE";
            case "secPwdValid" -> "TRUE";
            case "secValidUntil" -> "nowPlusYears(" + years + ")";
            case "secPwdLastChanged" -> "now()";
            case "secUUID" -> "uuid()";
            case "principalName" -> "${user.uid}";
            // <secAuthority>%<uid> — secAuthority via a ${sec.*} reference (so
            // the dependency resolver composes it), uid straight from the
            // demographic payload. Matches the pre-model Default%<uid> shape
            // without requiring principalName to be enabled.
            case "secDomainId" -> "${sec.secAuthority}%${user.uid}";
            default -> throw new IllegalStateException("Unknown overlay attribute: " + name);
        };
    }

    /**
     * Resolve every enabled attribute to its concrete value for one user, in
     * the effective model's order. {@code COMPUTED} values are evaluated via
     * {@link SecUserExpressionEvaluator}; {@code ${sec.*}} references are
     * resolved on demand against the same model, so a value that depends on
     * another (e.g. {@code secDomainId} → {@code secAuthority}) resolves in
     * dependency order. Cyclic references and references to a disabled /
     * unknown attribute fail loudly at plan time.
     */
    public static Map<String, String> resolveValues(VendorIntegrationIsvaConfig cfg,
                                                     Map<String, List<String>> userAttrs,
                                                     Instant now) {
        List<SecUserAttribute> attrs = effectiveAttributes(cfg);
        Map<String, SecUserAttribute> byName = new LinkedHashMap<>();
        for (SecUserAttribute a : attrs) {
            if (a.enabled()) {
                byName.put(a.name().toLowerCase(Locale.ROOT), a);
            }
        }
        Map<String, String> memo = new java.util.HashMap<>();
        Set<String> visiting = new LinkedHashSet<>();
        Map<String, String> out = new LinkedHashMap<>();
        for (SecUserAttribute a : attrs) {
            if (a.enabled()) {
                out.put(a.name(),
                        resolveOne(a.name(), byName, userAttrs, now, memo, visiting));
            }
        }
        return out;
    }

    private static String resolveOne(String name,
                                     Map<String, SecUserAttribute> byName,
                                     Map<String, List<String>> userAttrs,
                                     Instant now,
                                     Map<String, String> memo,
                                     Set<String> visiting) {
        String key = name.toLowerCase(Locale.ROOT);
        String done = memo.get(key);
        if (done != null) {
            return done;
        }
        if (visiting.contains(key)) {
            throw new IllegalArgumentException(
                    "Cyclic secUser attribute expression involving '" + name + "'");
        }
        SecUserAttribute a = byName.get(key);
        if (a == null) {
            throw new IllegalArgumentException(
                    "secUser attribute expression references '" + name
                            + "', which is not a written attribute in this config. "
                            + "Enable it, or reference a base user attribute "
                            + "(${user." + name + "}) instead.");
        }
        visiting.add(key);
        String value = a.valueKind() == SecUserAttributeValueKind.LITERAL
                ? nonNull(a.value(), "")
                : SecUserExpressionEvaluator.evaluate(a.value(), userAttrs,
                        dep -> resolveOne(dep, byName, userAttrs, now, memo, visiting), now);
        visiting.remove(key);
        memo.put(key, value);
        return value;
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
        return ModifyStep.of(secUserDn, disableMods(cfg));
    }

    /**
     * Re-enable the ISVA account: the inverse of {@link #disable} —
     * {@code secAcctValid=TRUE}, and — when the directory's overlay
     * includes it — {@code secValidUntil} pushed back out to
     * {@code now + defaultValidUntilYears} (undoing the {@code =now}
     * expiry a mirror-disable stamped). Used by the lifecycle-mirror
     * path so re-enabling a demographic user restores its secUser to a
     * valid, unexpired state.
     */
    public ModifyStep enable(String secUserDn, VendorIntegrationIsvaConfig cfg) {
        return ModifyStep.of(secUserDn, enableMods(cfg));
    }

    /**
     * The {@code sec*} modifications that mirror an account enable /
     * disable onto the secUser side, without wrapping them in a step —
     * so the inline lifecycle path can fold them into the same MODIFY
     * that writes the demographic entry's enable/disable attribute (in
     * inline mode the secUser <em>is</em> the demographic entry).
     */
    public List<Modification> setEnabledMods(VendorIntegrationIsvaConfig cfg, boolean enabled) {
        return enabled ? enableMods(cfg) : disableMods(cfg);
    }

    private List<Modification> disableMods(VendorIntegrationIsvaConfig cfg) {
        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification(ModificationType.REPLACE, "secAcctValid", "FALSE"));
        if (overlayEnabled(cfg, "secValidUntil")) {
            mods.add(new Modification(ModificationType.REPLACE, "secValidUntil",
                    generalizedTime(Instant.now())));
        }
        return mods;
    }

    private List<Modification> enableMods(VendorIntegrationIsvaConfig cfg) {
        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification(ModificationType.REPLACE, "secAcctValid", "TRUE"));
        if (overlayEnabled(cfg, "secValidUntil")) {
            mods.add(new Modification(ModificationType.REPLACE, "secValidUntil",
                    generalizedTime(Instant.now().plusSeconds(
                            yearsInSeconds(cfg.getDefaultValidUntilYears())))));
        }
        return mods;
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
        for (Map.Entry<String, String> e : secDefaults(cfg, payload.attributes()).entrySet()) {
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
    private Map<String, String> secDefaults(VendorIntegrationIsvaConfig cfg,
                                            Map<String, List<String>> userAttrs) {
        // The unified per-attribute model is the single source of truth for
        // which attributes a grant emits and how each value is produced —
        // literal, or a computed expression evaluated against this user. One
        // shared Instant.now() keeps secValidUntil / secPwdLastChanged on the
        // same entry agreeing to the microsecond, as before.
        return resolveValues(cfg, userAttrs, Instant.now());
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
