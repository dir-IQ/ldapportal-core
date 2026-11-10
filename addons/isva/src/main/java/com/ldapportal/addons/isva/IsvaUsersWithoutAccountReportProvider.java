// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.core.reports.OperationalReportProvider;
import com.ldapportal.core.reports.ReportData;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryObjectClassDefaults;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operational report: <b>users without an IVIA account</b> — directory users
 * that have no paired IVIA account (linked mode: no secUser whose
 * {@code secDN} names them; inline mode: no {@code secUser} objectClass on
 * the entry). The reverse of {@link IsvaOrphanedAccountsReportProvider},
 * which lists secUsers whose demographic entry is gone.
 *
 * <p>Reads users through the enriched user search and keeps those the
 * {@link IsvaUserReadEnricher} flags {@value IsvaUserReadEnricher#HAS_ACCOUNT}
 * {@code = "false"}, so it agrees with the Users page's "No IVIA account"
 * chip. A user the enricher couldn't classify is not listed.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsvaUsersWithoutAccountReportProvider implements OperationalReportProvider {

    /** Report id the client sends as the report type. Must not collide with
     *  any {@code OperationalReportType} enum name. */
    public static final String REPORT_ID = "USERS_WITHOUT_IVIA_ACCOUNT";

    /** Users read per report, as the built-in user reports cap it. */
    static final int MAX_USERS = 10_000;

    /** Report columns, keyed by attribute name like the built-in user reports. */
    static final List<String> COLUMNS = List.of("dn", "uid", "cn", "mail");

    private final LdapUserService userService;
    private final IsvaUserReadEnricher enricher;

    @Override
    public String reportId() {
        return REPORT_ID;
    }

    @Override
    public boolean appliesTo(DirectoryConnection dir) {
        return enricher.flagsAccounts(dir);
    }

    @Override
    public ReportData run(DirectoryConnection dir, Map<String, Object> params, String scopeBaseDn) {
        if (!appliesTo(dir)) {
            // appliesTo() gates this in the normal flow; defensive for direct calls.
            throw new IllegalArgumentException(
                    "Users without an IVIA account report requires an active IVIA "
                            + "configuration on directory " + dir.getDisplayName() + ".");
        }
        // objectClass lets the enricher judge inline-mode entries.
        List<LdapUser> users = userService.searchUsers(dir,
                DirectoryObjectClassDefaults.userSearchFilter(dir), scopeBaseDn, MAX_USERS,
                "uid", "cn", "mail", "objectClass");

        List<Map<String, String>> rows = new ArrayList<>();
        for (LdapUser u : users) {
            if (!"false".equals(u.getFirstValue(IsvaUserReadEnricher.HAS_ACCOUNT))) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("dn", u.getDn());
            for (String attr : COLUMNS.subList(1, COLUMNS.size())) {
                row.put(attr, String.join("|", u.getValues(attr)));
            }
            rows.add(row);
        }
        log.info("Users without an IVIA account on directory {}: {} of {} user(s)",
                dir.getDisplayName(), rows.size(), users.size());
        return new ReportData(COLUMNS, rows);
    }
}
