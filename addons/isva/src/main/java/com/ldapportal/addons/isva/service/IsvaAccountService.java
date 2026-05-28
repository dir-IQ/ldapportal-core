// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.IsvaSecUserPlans;
import com.ldapportal.addons.isva.dto.IsvaAccountStatus;
import com.ldapportal.addons.isva.dto.IsvaRevokeMode;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.provisioning.AddStep;
import com.ldapportal.core.provisioning.LdapOperationStep;
import com.ldapportal.core.provisioning.PlanExecutor;
import com.ldapportal.core.provisioning.StepFailurePolicy;
import com.ldapportal.core.provisioning.UserCreatePayload;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import com.ldapportal.service.ProvisioningProfileService;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Account-scoped verbs on an identity that <em>already exists</em> in
 * the directory — grant / revoke / suspend / restore / renew /
 * force-credential-reset. Each verb is a small plan composed from
 * {@link IsvaSecUserPlans} fragments, run through {@link PlanExecutor},
 * and audited with a {@code source: ivia} discriminator.
 *
 * <p>This service is decoupled from demographic create/delete on
 * purpose: those flows live in {@code LdapOperationService} and write
 * the demographic entry. The verbs here act only on the IVIA side
 * (the secUser overlay in inline mode, the paired secUser entry in
 * linked mode), and refuse cleanly when the directory has no IVIA
 * config (409 {@code ivia_directory_disabled}), the resolved profile
 * is exempt (409 {@code ivia_force_off} — grant only), or the IVIA
 * state isn't what the verb wants (409 {@code ivia_orphan} /
 * {@code ivia_already_linked}).</p>
 *
 * <h2>Topology routing</h2>
 *
 * <p>Inline mode: every verb targets the demographic DN — the
 * {@code secUser} objectClass + {@code sec*} overlay live there.
 * Linked mode: grant ADDs a new secUser entry under the management
 * DIT; the lifecycle verbs (suspend/restore/renew/reset) target the
 * paired secUser DN resolved via {@link com.ldapportal.addons.isva.IsvaLinkedUserLookup};
 * hard revoke deletes the secUser entry.</p>
 *
 * <h2>Idempotency policy</h2>
 *
 * <p>Per verb:
 * <ul>
 *   <li>{@code grant} on an already-granted account → refuse 409
 *       ({@code ivia_already_linked}). Refusing a redundant grant is
 *       a deliberate choice, not idempotency — operator intent matters.</li>
 *   <li>{@code revoke} on an orphan → refuse 409
 *       ({@code ivia_orphan}). Points the operator at integrity
 *       reconcile instead.</li>
 *   <li>{@code suspend} on an already-suspended account → no-op 200
 *       (the LDAP MODIFY REPLACE is naturally idempotent).</li>
 *   <li>{@code restore} on an already-active account → no-op 200.</li>
 *   <li>{@code renew} with a date earlier than {@code secValidUntil}
 *       → refuse 400 ({@code ivia_renew_not_forward}); equal → no-op
 *       200.</li>
 *   <li>{@code forceCredentialReset} with {@code secPwdValid} already
 *       FALSE → no-op 200.</li>
 * </ul></p>
 *
 * <h2>TOCTOU translation</h2>
 *
 * <p>The probe-then-act pattern races other operators. If a concurrent
 * verb has flipped the state between our probe and our write, LDAP
 * surfaces {@code NO_SUCH_OBJECT} (on revoke/suspend/etc) or
 * {@code ATTRIBUTE_OR_VALUE_EXISTS} / {@code ENTRY_ALREADY_EXISTS}
 * (on grant). All three are translated to 409
 * {@code ivia_state_changed} so the user sees "refresh and retry"
 * instead of a generic 422.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IsvaAccountService {

    /** Hard cap on {@code renew} — refuse far-future dates so a typo
     * doesn't grant a century of validity. The default-config value
     * ({@code default_valid_until_years = 100}) is intentionally
     * generous and applied only by grant; renew is operator-driven and
     * gets a tighter cap. */
    private static final int RENEW_MAX_YEARS_AHEAD = 10;

    private final VendorIntegrationIsvaConfigRepository configRepo;
    private final DirectoryConnectionRepository dirRepo;
    private final IsvaSecUserPlans secUserPlans;
    private final IsvaAccountStatusProbe probe;
    private final PlanExecutor planExecutor;
    private final ProvisioningProfileService profileService;
    private final IsvaProfileOverrideService overrideService;
    private final AuditService auditService;

    // ── public surface ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public IsvaAccountStatus getStatus(UUID directoryId, String dn) {
        Ctx ctx = loadContext(directoryId);
        return probe.probe(ctx.dir(), ctx.cfg(), dn);
    }

    @Transactional
    public IsvaAccountStatus grant(UUID directoryId, String dn, AuthPrincipal principal) {
        Ctx ctx = loadContext(directoryId);
        // Grant is the one verb that respects the profile FORCE_OFF
        // narrowing — the other verbs are always-allowed because they
        // narrow the IVIA state further.
        requireNotForceOff(ctx.dir(), dn);
        IsvaAccountStatus status = probe.probe(ctx.dir(), ctx.cfg(), dn);
        if (status.linked()) {
            throw refuse(HttpStatus.CONFLICT, "ivia_already_linked",
                    "An IVIA account already exists for " + dn);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "ivia");
        detail.put("ivia_op", "grant");
        LdapOperationStep step;
        if (ctx.cfg().getTopologyMode() == IsvaTopologyMode.INLINE) {
            String uid = probe.resolveUid(ctx.dir(), dn);
            step = secUserPlans.grantInlineOnExisting(dn, ctx.cfg(), uid);
            detail.put("mode", "inline");
        } else {
            String uid = probe.resolveUid(ctx.dir(), dn);
            UserCreatePayload synthetic = UserCreatePayload.of(
                    dn, Map.of("uid", List.of(uid)));
            AddStep linkedAdd = secUserPlans.grantLinked(ctx.cfg(), synthetic);
            // grantLinked tags COMPENSATE so a failed lifecycle-create rolls
            // back the demographic ADD. Account-management grant has no
            // preceding step to compensate against; re-stamp ABORT so a
            // failure here just bubbles as a 422 / 409, not a misleading
            // "compensation attempted" warning.
            step = new AddStep(linkedAdd.targetDn(), linkedAdd.attributes(),
                    StepFailurePolicy.ABORT);
            detail.put("mode", "linked");
            detail.put("secUserDn", linkedAdd.targetDn());
        }

        runStep(ctx.dir(), step);
        auditService.record(principal, directoryId,
                AuditAction.USER_UPDATE, dn, detail);
        // Re-probe so the caller sees the fresh snapshot. Cheap (one
        // LDAP round-trip in inline mode, two in linked) and avoids
        // hand-rolling a status from local-only state that might race
        // with another operator.
        return probe.probe(ctx.dir(), ctx.cfg(), dn);
    }

    @Transactional
    public IsvaAccountStatus revoke(UUID directoryId,
                                    String dn,
                                    IsvaRevokeMode mode,
                                    AuthPrincipal principal) {
        Ctx ctx = loadContext(directoryId);
        IsvaAccountStatus status = probe.probe(ctx.dir(), ctx.cfg(), dn);
        if (status.orphaned()) {
            throw refuseOrphan();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "ivia");
        detail.put("mode", ctx.cfg().getTopologyMode() == IsvaTopologyMode.INLINE
                ? "inline" : "linked");

        LdapOperationStep step;
        AuditAction action;
        if (mode == IsvaRevokeMode.SOFT) {
            String target = lifecycleTarget(status, dn);
            step = secUserPlans.disable(target);
            action = AuditAction.USER_DISABLE;
            detail.put("ivia_op", "revoke_soft");
        } else {
            if (ctx.cfg().getTopologyMode() == IsvaTopologyMode.INLINE) {
                step = secUserPlans.revokeInlineOnExisting(dn);
            } else {
                step = secUserPlans.hardDelete(status.secUserDn());
            }
            action = AuditAction.USER_DELETE;
            detail.put("ivia_op", "revoke_hard");
        }

        runStep(ctx.dir(), step);
        auditService.record(principal, directoryId, action, dn, detail);
        return probe.probe(ctx.dir(), ctx.cfg(), dn);
    }

    @Transactional
    public IsvaAccountStatus suspend(UUID directoryId, String dn, AuthPrincipal principal) {
        Ctx ctx = loadContext(directoryId);
        IsvaAccountStatus status = probe.probe(ctx.dir(), ctx.cfg(), dn);
        if (status.orphaned()) {
            throw refuseOrphan();
        }
        if (!status.acctValid()) {
            // Already suspended — no-op 200. Re-probe so the caller still
            // gets a fresh, accurate snapshot (paranoia against the race
            // where the suspended state was the result of a concurrent
            // op that also flipped other fields).
            return status;
        }
        runStep(ctx.dir(), secUserPlans.suspend(lifecycleTarget(status, dn)));
        auditService.record(principal, directoryId, AuditAction.USER_DISABLE, dn,
                iviaDetail(ctx.cfg(), "suspend"));
        return probe.probe(ctx.dir(), ctx.cfg(), dn);
    }

    @Transactional
    public IsvaAccountStatus restore(UUID directoryId, String dn, AuthPrincipal principal) {
        Ctx ctx = loadContext(directoryId);
        IsvaAccountStatus status = probe.probe(ctx.dir(), ctx.cfg(), dn);
        if (status.orphaned()) {
            throw refuseOrphan();
        }
        if (status.acctValid()) {
            return status;
        }
        runStep(ctx.dir(), secUserPlans.restore(lifecycleTarget(status, dn)));
        auditService.record(principal, directoryId, AuditAction.USER_ENABLE, dn,
                iviaDetail(ctx.cfg(), "restore"));
        return probe.probe(ctx.dir(), ctx.cfg(), dn);
    }

    @Transactional
    public IsvaAccountStatus renew(UUID directoryId,
                                   String dn,
                                   OffsetDateTime newValidUntil,
                                   AuthPrincipal principal) {
        Ctx ctx = loadContext(directoryId);
        IsvaAccountStatus status = probe.probe(ctx.dir(), ctx.cfg(), dn);
        if (status.orphaned()) {
            throw refuseOrphan();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (newValidUntil.isBefore(now)) {
            throw refuse(HttpStatus.BAD_REQUEST, "ivia_renew_not_forward",
                    "validUntil must be in the future");
        }
        OffsetDateTime maxAhead = now.plusYears(RENEW_MAX_YEARS_AHEAD);
        if (newValidUntil.isAfter(maxAhead)) {
            throw refuse(HttpStatus.BAD_REQUEST, "ivia_renew_not_forward",
                    "validUntil more than " + RENEW_MAX_YEARS_AHEAD + " years ahead is not allowed");
        }
        OffsetDateTime current = status.validUntil();
        if (current != null) {
            if (newValidUntil.isBefore(current)) {
                throw refuse(HttpStatus.BAD_REQUEST, "ivia_renew_not_forward",
                        "validUntil must extend forward — current is "
                                + current + ", requested " + newValidUntil);
            }
            if (newValidUntil.isEqual(current)) {
                return status;
            }
        }
        runStep(ctx.dir(),
                secUserPlans.renew(lifecycleTarget(status, dn),
                        newValidUntil.toInstant()));
        Map<String, Object> detail = iviaDetail(ctx.cfg(), "renew");
        detail.put("validUntil", newValidUntil.toString());
        auditService.record(principal, directoryId, AuditAction.USER_UPDATE, dn, detail);
        return probe.probe(ctx.dir(), ctx.cfg(), dn);
    }

    @Transactional
    public IsvaAccountStatus forceCredentialReset(UUID directoryId,
                                                  String dn,
                                                  AuthPrincipal principal) {
        Ctx ctx = loadContext(directoryId);
        IsvaAccountStatus status = probe.probe(ctx.dir(), ctx.cfg(), dn);
        if (status.orphaned()) {
            throw refuseOrphan();
        }
        if (!status.pwdValid()) {
            return status;
        }
        runStep(ctx.dir(), secUserPlans.forceCredentialReset(lifecycleTarget(status, dn)));
        auditService.record(principal, directoryId, AuditAction.PASSWORD_RESET, dn,
                iviaDetail(ctx.cfg(), "force_reset"));
        return probe.probe(ctx.dir(), ctx.cfg(), dn);
    }

    // ── internals ───────────────────────────────────────────────────

    private Ctx loadContext(UUID directoryId) {
        DirectoryConnection dir = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Directory not found: " + directoryId));
        VendorIntegrationIsvaConfig cfg = configRepo.findById(directoryId)
                .filter(VendorIntegrationIsvaConfig::isEnabled)
                .orElseThrow(() -> refuse(HttpStatus.CONFLICT, "ivia_directory_disabled",
                        "Directory [" + dir.getDisplayName()
                                + "] has no active IVIA integration — enable IVIA on the directory first"));
        return new Ctx(dir, cfg);
    }

    /**
     * Where the lifecycle MODIFY lands: in inline mode the demographic
     * entry carries the {@code sec*} overlay so the MODIFY targets
     * {@code dn}; in linked mode the paired secUser DN from the probe
     * is the target. Returning the demographic DN as a fallback in
     * linked mode would silently MODIFY the wrong entry, so we hard-
     * fail if the probe didn't surface a secUserDn.
     */
    private String lifecycleTarget(IsvaAccountStatus status, String demographicDn) {
        if (status.topology() == IsvaTopologyMode.INLINE) {
            return demographicDn;
        }
        if (status.secUserDn() == null) {
            // Probe said present but didn't fill secUserDn — a defect in
            // the probe, not an LDAP race. Bail loudly rather than guess.
            throw new IllegalStateException(
                    "Linked-mode probe returned present=true but no secUserDn for " + demographicDn);
        }
        return status.secUserDn();
    }

    private void requireNotForceOff(DirectoryConnection dir, String dn) {
        Optional<ProvisioningProfile> resolved =
                profileService.resolveProfileForDn(dir.getId(), dn);
        UUID profileId = resolved.map(ProvisioningProfile::getId).orElse(null);
        if (overrideService.isExemptFromIvia(profileId)) {
            throw refuse(HttpStatus.CONFLICT, "ivia_force_off",
                    "The provisioning profile covering " + dn
                            + " has IVIA set to FORCE_OFF — flip the profile override first");
        }
    }

    /**
     * Executes one LDAP step via {@link PlanExecutor} and translates
     * known TOCTOU errors into a 409 {@code ivia_state_changed}.
     * Other LDAP failures bubble through as
     * {@link LdapOperationException} (mapped to 422 by the global
     * handler).
     */
    private void runStep(DirectoryConnection dir, LdapOperationStep step) {
        try {
            planExecutor.execute(dir, step);
        } catch (LdapOperationException ex) {
            if (isStateChanged(ex)) {
                throw refuse(HttpStatus.CONFLICT, "ivia_state_changed",
                        "IVIA state changed under us — refresh and retry");
            }
            throw ex;
        }
    }

    /**
     * True if the underlying LDAP failure indicates the entry's state
     * isn't what the probe said it was — another operator ran the
     * opposite verb between our probe and our write.
     */
    private static boolean isStateChanged(LdapOperationException ex) {
        Throwable cause = ex.getCause();
        if (!(cause instanceof LDAPException ldap)) {
            return false;
        }
        ResultCode rc = ldap.getResultCode();
        return rc == ResultCode.NO_SUCH_OBJECT
                || rc == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS
                || rc == ResultCode.ENTRY_ALREADY_EXISTS;
    }

    private IsvaAccountRefusalException refuseOrphan() {
        return refuse(HttpStatus.CONFLICT, "ivia_orphan",
                "No IVIA account exists for this identity — run integrity reconcile to repair");
    }

    private static IsvaAccountRefusalException refuse(HttpStatus status,
                                                       String code,
                                                       String message) {
        return new IsvaAccountRefusalException(status, code, message);
    }

    private Map<String, Object> iviaDetail(VendorIntegrationIsvaConfig cfg, String op) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "ivia");
        detail.put("ivia_op", op);
        detail.put("mode", cfg.getTopologyMode() == IsvaTopologyMode.INLINE
                ? "inline" : "linked");
        return detail;
    }

    /** Loaded once per verb call. */
    private record Ctx(DirectoryConnection dir, VendorIntegrationIsvaConfig cfg) {}
}
