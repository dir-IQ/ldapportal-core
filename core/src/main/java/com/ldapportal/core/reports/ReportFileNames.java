// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import java.util.Locale;

/**
 * The one naming rule for report files, shared by the on-demand download
 * ({@link ReportController}) and the scheduled renderers, so a report is named
 * the same whichever way it was produced: its label slugged, plus the format's
 * extension ({@code "Disabled Accounts"} → {@code disabled-accounts.csv}).
 */
public final class ReportFileNames {

    private ReportFileNames() {
    }

    /**
     * The human-readable label for a report id: {@code "USERS_IN_GROUP"} →
     * {@code "Users In Group"}, {@code "ivia.orphaned_accounts"} →
     * {@code "Ivia Orphaned Accounts"}.
     */
    public static String label(String id) {
        String[] words = id.replaceAll("[._]", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)))
              .append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.length() == 0 ? id : sb.toString();
    }

    /** {@code ("Disabled Accounts", "csv")} → {@code "disabled-accounts.csv"}. */
    public static String forLabel(String label, String extension) {
        String slug = label == null ? "" : label.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return (slug.isEmpty() ? "report" : slug) + "." + extension;
    }

    /** The file name for report id {@code reportId}, via its {@link #label(String)}. */
    public static String forReportId(String reportId, String extension) {
        return forLabel(label(reportId), extension);
    }
}
