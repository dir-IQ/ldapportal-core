// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.unboundid.ldap.sdk.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PasswordPolicyService {

    private final DirectoryConnectionRepository dirRepo;
    private final PermissionService permissionService;
    private final LdapConnectionFactory connectionFactory;

    /** Operational attributes that ppolicy overlay populates on user entries. */
    private static final String[] PPOLICY_USER_ATTRS = {
            "pwdChangedTime", "pwdAccountLockedTime", "pwdFailureTime",
            "pwdGraceUseTime", "pwdPolicySubentry", "pwdReset"
    };

    /** Attributes on the pwdPolicy entry itself. */
    private static final String[] PPOLICY_ENTRY_ATTRS = {
            "pwdMaxAge", "pwdMaxFailure", "pwdLockoutDuration",
            "pwdGraceAuthNLimit", "pwdMinLength", "pwdInHistory",
            "pwdExpireWarning", "pwdLockout"
    };

    public Map<String, Object> getPasswordStatus(UUID directoryId, AuthPrincipal principal, String userDn) {
        DirectoryConnection dc = dirRepo.findById(directoryId)
                .orElseThrow(() -> new com.ldapportal.exception.ResourceNotFoundException("Directory not found"));
        permissionService.requireDirectoryAccess(principal, directoryId);

        return connectionFactory.withConnection(dc, conn -> {
            Map<String, Object> result = new LinkedHashMap<>();

            // Fetch operational attributes from the user entry
            SearchResultEntry userEntry;
            try {
                userEntry = conn.getEntry(userDn, PPOLICY_USER_ATTRS);
            } catch (LDAPException e) {
                log.warn("Failed to read ppolicy attrs for {}: {}", userDn, e.getMessage());
                result.put("supported", false);
                return result;
            }

            if (userEntry == null) {
                result.put("supported", false);
                return result;
            }

            String pwdChangedTime = userEntry.getAttributeValue("pwdChangedTime");
            String pwdAccountLockedTime = userEntry.getAttributeValue("pwdAccountLockedTime");
            String[] pwdFailureTimes = userEntry.getAttributeValues("pwdFailureTime");
            String[] pwdGraceUseTimes = userEntry.getAttributeValues("pwdGraceUseTime");
            String pwdPolicySubentry = userEntry.getAttributeValue("pwdPolicySubentry");
            String pwdReset = userEntry.getAttributeValue("pwdReset");

            boolean hasAnyPolicyData = pwdChangedTime != null || pwdAccountLockedTime != null
                    || pwdPolicySubentry != null;

            if (!hasAnyPolicyData) {
                result.put("supported", false);
                return result;
            }

            result.put("supported", true);

            // Last changed
            if (pwdChangedTime != null) {
                OffsetDateTime changed = parseGeneralizedTime(pwdChangedTime);
                result.put("lastChanged", changed);
                result.put("daysSinceChange", ChronoUnit.DAYS.between(changed.toInstant(), Instant.now()));
            }

            // Locked
            result.put("isLocked", pwdAccountLockedTime != null);
            if (pwdAccountLockedTime != null) {
                result.put("lockedSince", parseGeneralizedTime(pwdAccountLockedTime));
            }

            // Failed attempts
            result.put("failedAttempts", pwdFailureTimes != null ? pwdFailureTimes.length : 0);

            // Grace logins used
            result.put("graceLoginsUsed", pwdGraceUseTimes != null ? pwdGraceUseTimes.length : 0);

            // Must change (pwdReset=TRUE means admin reset, user must change)
            result.put("mustChange", "TRUE".equalsIgnoreCase(pwdReset));

            // Try to read the password policy entry for expiration calculation
            if (pwdPolicySubentry != null) {
                result.put("policyDn", pwdPolicySubentry);
                try {
                    SearchResultEntry policyEntry = conn.getEntry(pwdPolicySubentry, PPOLICY_ENTRY_ATTRS);
                    if (policyEntry != null) {
                        Integer maxAge = policyEntry.getAttributeValueAsInteger("pwdMaxAge");
                        Integer maxFailure = policyEntry.getAttributeValueAsInteger("pwdMaxFailure");
                        Integer lockoutDuration = policyEntry.getAttributeValueAsInteger("pwdLockoutDuration");
                        Integer graceLimit = policyEntry.getAttributeValueAsInteger("pwdGraceAuthNLimit");
                        Integer minLength = policyEntry.getAttributeValueAsInteger("pwdMinLength");
                        Integer expireWarning = policyEntry.getAttributeValueAsInteger("pwdExpireWarning");

                        if (maxAge != null && maxAge > 0) {
                            result.put("maxAgeDays", maxAge / 86400);
                            if (pwdChangedTime != null) {
                                OffsetDateTime changed = parseGeneralizedTime(pwdChangedTime);
                                OffsetDateTime expires = changed.plusSeconds(maxAge);
                                result.put("expiresAt", expires);
                                long daysUntil = ChronoUnit.DAYS.between(Instant.now(), expires.toInstant());
                                result.put("daysUntilExpiry", daysUntil);
                                result.put("isExpired", daysUntil < 0);
                            }
                        }

                        if (maxFailure != null) result.put("maxFailures", maxFailure);
                        if (lockoutDuration != null) result.put("lockoutDurationSeconds", lockoutDuration);
                        if (graceLimit != null) {
                            result.put("graceLoginLimit", graceLimit);
                            int used = pwdGraceUseTimes != null ? pwdGraceUseTimes.length : 0;
                            result.put("graceLoginsRemaining", Math.max(0, graceLimit - used));
                        }
                        if (minLength != null) result.put("minLength", minLength);
                        if (expireWarning != null) result.put("expireWarningSeconds", expireWarning);
                    }
                } catch (LDAPException e) {
                    log.debug("Could not read ppolicy entry {}: {}", pwdPolicySubentry, e.getMessage());
                }
            }

            return result;
        });
    }

    private static OffsetDateTime parseGeneralizedTime(String gt) {
        // LDAP generalized time: 20250315143022Z or 20250315143022.123Z
        try {
            String cleaned = gt.replaceAll("\\.\\d+", ""); // strip fractional seconds
            if (cleaned.endsWith("Z")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
            return java.time.LocalDateTime.parse(cleaned, fmt).atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            log.debug("Failed to parse generalized time '{}': {}", gt, e.getMessage());
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
