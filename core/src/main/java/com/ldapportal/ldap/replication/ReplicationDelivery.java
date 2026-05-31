// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Applies one {@link ReplicationEventSnapshot} to its target directory.
 * Stateless; the worker (see {@code ReplicationWorker}) calls
 * {@link #deliver} per claimed event and translates the result into
 * a status transition.
 *
 * <p>Uses {@link LdapConnectionFactory#withConnectionUnreplicated} so
 * the target-side write doesn't loop back through the
 * {@code ReplicatingLdapInterface} wrapper — replication of
 * replication-applied writes would silently double-enqueue events.
 *
 * <p>{@code auto_create_on_missing} (per-link flag): when a MODIFY
 * targets an entry the target doesn't have (NO_SUCH_OBJECT), and the
 * link allows it, the delivery re-reads the entry from the source
 * directory and re-attempts as an ADD before failing.
 *
 * <p>This bean has no JPA repository dependency — it operates entirely
 * on the immutable snapshot the worker hands it. The link's attribute-
 * mapping rules are already materialised inside the snapshot's
 * {@code attributeMappings} list, so the auto-create path needs no
 * extra DB round-trip.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@LdapWriteAuthorized("Target-side replication apply via withConnectionUnreplicated "
        + "(deliberately uncaptured so replicated writes don't loop back).")
public class ReplicationDelivery {

    private final LdapConnectionFactory connectionFactory;

    /**
     * Short retry budget for the post-auto-create MODIFY against
     * the target. Covers replica-lag windows where the freshly-added
     * entry hasn't propagated to the replica the MODIFY lands on.
     * 3 attempts × 500ms gap = up to ~1s extra latency in the
     * pathological case; the common case is one attempt that succeeds.
     */
    private static final int POST_CREATE_MODIFY_MAX_ATTEMPTS = 3;
    private static final long POST_CREATE_MODIFY_RETRY_MS = 500L;

    /**
     * Apply {@code event} to {@code event.link().targetDirectory()}.
     * Returns success/failure. Caller maps to status transitions.
     */
    public DeliveryResult deliver(ReplicationEventSnapshot event) {
        ReplicationLinkSnapshot link = event.link();
        DirectoryConnection target = link.targetDirectory();
        try {
            return switch (event.operation()) {
                case ADD       -> deliverAdd(event, target);
                case MODIFY    -> deliverModify(event, link, target);
                case DELETE    -> deliverDelete(event, target);
                case MODIFY_DN -> deliverModifyDn(event, target);
            };
        } catch (Exception ex) {
            // withConnectionUnreplicated wraps inner LDAPException as
            // LdapConnectionException / LdapOperationException; any
            // remaining surface (pool issues, decrypt failures on the
            // target's bind password, NPE in payload decode, etc) lands
            // here too. Surface every flavour as a clean delivery
            // failure so nothing escapes into the worker's @Scheduled
            // method — a thrown exception there would stop the loop
            // entirely until process restart.
            ResultCode rc = (ex.getCause() instanceof LDAPException le) ? le.getResultCode() : null;
            return DeliveryResult.fail(rc, ex.getMessage());
        }
    }

    private DeliveryResult deliverAdd(ReplicationEventSnapshot event, DirectoryConnection target) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            LDAPResult r = conn.add(ReplicationPayloadCodec.decodeAdd(
                    event.targetDn(), event.payload()));
            return interpret(r);
        });
    }

    private DeliveryResult deliverModify(ReplicationEventSnapshot event,
                                          ReplicationLinkSnapshot link,
                                          DirectoryConnection target) {
        try {
            return modifyOnce(event, target);
        } catch (com.ldapportal.exception.LdapOperationException ex) {
            // Inner LDAPException is wrapped — fish it back out to
            // check for NO_SUCH_OBJECT, the trigger for the auto-create
            // path. Other operation errors propagate as a normal
            // failure via the catch in deliver().
            Throwable cause = ex.getCause();
            if (cause instanceof LDAPException le
                && le.getResultCode() == ResultCode.NO_SUCH_OBJECT
                && link.autoCreateOnMissing()) {
                return autoCreateThenModify(event, link, target);
            }
            throw ex;
        }
    }

    private DeliveryResult modifyOnce(ReplicationEventSnapshot event, DirectoryConnection target) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            LDAPResult r = conn.modify(ReplicationPayloadCodec.decodeModify(
                    event.targetDn(), event.payload()));
            return interpret(r);
        });
    }

    /**
     * Auto-create path: source-side SUB read to reconstruct the entry,
     * apply attribute mapping, attempt ADD against the target, then
     * re-attempt the original MODIFY. Falls back to a clean failure
     * if either step doesn't produce success.
     */
    private DeliveryResult autoCreateThenModify(ReplicationEventSnapshot event,
                                                  ReplicationLinkSnapshot link,
                                                  DirectoryConnection target) {
        DirectoryConnection source = link.sourceDirectory();
        SearchResultEntry sourceEntry;
        try {
            sourceEntry = connectionFactory.withConnectionUnreplicated(source, conn ->
                    conn.getEntry(event.sourceDn()));
        } catch (Exception ex) {
            return DeliveryResult.fail(null,
                    "auto-create: source read failed: " + ex.getMessage());
        }
        if (sourceEntry == null) {
            return DeliveryResult.fail(ResultCode.NO_SUCH_OBJECT,
                    "auto-create: source entry no longer exists");
        }

        // Apply attribute mapping to the source entry's attribute set
        // so the ADD targets the right names / values. The link
        // snapshot already carries the materialised attributeMappings
        // — no DB round-trip needed.
        Map<String, List<String>> raw = new java.util.LinkedHashMap<>();
        for (com.unboundid.ldap.sdk.Attribute a : sourceEntry.getAttributes()) {
            raw.put(a.getName(), Arrays.asList(a.getValues()));
        }
        Map<String, List<String>> mapped = AttributeMapper.mapAttributes(raw, link);
        List<com.unboundid.ldap.sdk.Attribute> attrs = new ArrayList<>(mapped.size());
        mapped.forEach((name, values) ->
                attrs.add(new com.unboundid.ldap.sdk.Attribute(name, values.toArray(new String[0]))));

        try {
            connectionFactory.withConnectionUnreplicated(target, conn -> {
                LDAPResult r = conn.add(new com.unboundid.ldap.sdk.AddRequest(
                        event.targetDn(), attrs));
                if (r.getResultCode() != ResultCode.SUCCESS) {
                    throw new LDAPException(r.getResultCode(),
                            "auto-create ADD failed: " + r.getDiagnosticMessage());
                }
                return null;
            });
        } catch (Exception ex) {
            return DeliveryResult.fail(null,
                    "auto-create: target ADD failed: " + ex.getMessage());
        }

        // Re-attempt the original MODIFY against the now-existing entry.
        // On a multi-replica target, the ADD lands on one replica and
        // the MODIFY may hit another that hasn't seen the ADD yet —
        // NO_SUCH_OBJECT recurs. Retry a few times with a small pause
        // (bounded budget — does not recurse into autoCreateThenModify)
        // so transient replica lag self-heals instead of falling into
        // backoff and head-of-line-blocking the link's FIFO.
        com.ldapportal.exception.LdapOperationException lastEx = null;
        for (int attempt = 1; attempt <= POST_CREATE_MODIFY_MAX_ATTEMPTS; attempt++) {
            try {
                return modifyOnce(event, target);
            } catch (com.ldapportal.exception.LdapOperationException ex) {
                lastEx = ex;
                boolean noSuchObject = ex.getCause() instanceof LDAPException le
                        && le.getResultCode() == ResultCode.NO_SUCH_OBJECT;
                if (!noSuchObject || attempt == POST_CREATE_MODIFY_MAX_ATTEMPTS) {
                    break;
                }
                try {
                    Thread.sleep(POST_CREATE_MODIFY_RETRY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        ResultCode rc = (lastEx != null && lastEx.getCause() instanceof LDAPException le)
                ? le.getResultCode() : null;
        return DeliveryResult.fail(rc,
                "auto-create: post-create MODIFY failed: "
                        + (lastEx == null ? "unknown" : lastEx.getMessage()));
    }

    private DeliveryResult deliverDelete(ReplicationEventSnapshot event, DirectoryConnection target) {
        try {
            return connectionFactory.withConnectionUnreplicated(target, conn -> {
                LDAPResult r = conn.delete(ReplicationPayloadCodec.decodeDelete(event.targetDn()));
                return interpret(r);
            });
        } catch (com.ldapportal.exception.LdapOperationException ex) {
            // NO_SUCH_OBJECT on DELETE is convergent — the entry doesn't
            // exist on the target, which is the state we wanted. Mark
            // as delivered so we don't retry forever.
            if (ex.getCause() instanceof LDAPException le
                && le.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                log.debug("Target already missing for DELETE {} — treating as delivered",
                        event.targetDn());
                return DeliveryResult.ok();
            }
            throw ex;
        }
    }

    private DeliveryResult deliverModifyDn(ReplicationEventSnapshot event, DirectoryConnection target) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            LDAPResult r = conn.modifyDN(ReplicationPayloadCodec.decodeModifyDn(
                    event.targetDn(), event.payload()));
            return interpret(r);
        });
    }

    private static DeliveryResult interpret(LDAPResult r) {
        if (r.getResultCode() == ResultCode.SUCCESS) {
            return DeliveryResult.ok();
        }
        return DeliveryResult.fail(r.getResultCode(), r.getDiagnosticMessage());
    }

    public record DeliveryResult(boolean success, ResultCode resultCode, String errorMessage) {
        // Static factories use non-accessor names — Java treats record
        // accessors and static methods as the same namespace for
        // signature comparison, so a static `success()` would collide
        // with the auto-generated `success()` boolean accessor.
        static DeliveryResult ok() { return new DeliveryResult(true, ResultCode.SUCCESS, null); }
        static DeliveryResult fail(ResultCode code, String message) {
            return new DeliveryResult(false, code, message);
        }
    }
}
