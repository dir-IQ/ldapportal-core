// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a {@code COMPUTED} {@link com.ldapportal.addons.isva.entity.SecUserAttribute}
 * value against one user's attributes at provisioning time. Deliberately
 * small — a template interpolator plus three functions — not a general
 * scripting language.
 *
 * <p>An expression is one of:</p>
 * <ul>
 *   <li>A <b>function call</b> (the whole value):
 *     <ul>
 *       <li>{@code uuid()} — a fresh random UUID (e.g. {@code secUUID});</li>
 *       <li>{@code now()} — the current time in LDAP generalized-time
 *           ({@code yyyyMMddHHmmss'Z'}, UTC), e.g. {@code secPwdLastChanged};</li>
 *       <li>{@code nowPlusYears(n)} — {@code now + n} years in generalized
 *           time, e.g. {@code secValidUntil}. {@code n} matches the legacy
 *           {@code 365.25}-day-year arithmetic exactly.</li>
 *     </ul>
 *   </li>
 *   <li>A <b>template</b> mixing literal text with references:
 *     <ul>
 *       <li>{@code ${user.<attr>}} — the first value of the demographic
 *           user's {@code <attr>} (case-insensitive), or empty when absent;</li>
 *       <li>{@code ${sec.<attr>}} — the resolved value of another
 *           {@code secUser} attribute in this config, which is how
 *           {@code secDomainId = ${sec.secAuthority}%${user.uid}} composes.
 *           The caller supplies the resolver so cross-attribute dependencies
 *           (and cycle detection) live in one place.</li>
 *     </ul>
 *     Literal characters — including the {@code %} in {@code secDomainId} —
 *     pass through untouched.
 *   </li>
 * </ul>
 *
 * <p>Function detection wins over template interpolation, so a bare
 * {@code uuid()} is never treated as literal text. Functions don't nest
 * inside templates — there's no use case, and keeping them mutually
 * exclusive keeps the grammar unambiguous.</p>
 */
public final class SecUserExpressionEvaluator {

    private SecUserExpressionEvaluator() {}

    private static final Pattern REF =
            Pattern.compile("\\$\\{\\s*(user|sec)\\.([A-Za-z0-9_-]+)\\s*}");
    private static final Pattern UUID_FN = Pattern.compile("^\\s*uuid\\(\\)\\s*$");
    private static final Pattern NOW_FN = Pattern.compile("^\\s*now\\(\\)\\s*$");
    private static final Pattern NOW_PLUS_YEARS_FN =
            Pattern.compile("^\\s*nowPlusYears\\(\\s*(\\d+)\\s*\\)\\s*$");

    /**
     * Evaluate {@code expression} for one user.
     *
     * @param expression the {@code COMPUTED} value to evaluate
     * @param userAttrs  the demographic user's attributes (LDAP name → values)
     * @param secRef     resolver for {@code ${sec.<attr>}} references — given
     *                   an attribute name, returns its resolved value (and is
     *                   responsible for ordering / cycle detection)
     * @param now        the reference instant, shared across a grant so
     *                   {@code now()} and {@code nowPlusYears(n)} on the same
     *                   entry agree to the microsecond
     */
    public static String evaluate(String expression,
                                  Map<String, List<String>> userAttrs,
                                  UnaryOperator<String> secRef,
                                  Instant now) {
        if (expression == null) {
            return "";
        }
        if (UUID_FN.matcher(expression).matches()) {
            return java.util.UUID.randomUUID().toString();
        }
        if (NOW_FN.matcher(expression).matches()) {
            return generalizedTime(now);
        }
        Matcher years = NOW_PLUS_YEARS_FN.matcher(expression);
        if (years.matches()) {
            int n = Integer.parseInt(years.group(1));
            return generalizedTime(now.plusSeconds(yearsInSeconds(n)));
        }
        return interpolate(expression, userAttrs, secRef);
    }

    private static String interpolate(String template,
                                      Map<String, List<String>> userAttrs,
                                      UnaryOperator<String> secRef) {
        Matcher m = REF.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String scope = m.group(1);
            String attr = m.group(2);
            String value = "user".equals(scope)
                    ? firstUserValue(userAttrs, attr)
                    : safe(secRef.apply(attr));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String firstUserValue(Map<String, List<String>> userAttrs, String attr) {
        if (userAttrs == null) {
            return "";
        }
        // Case-insensitive lookup — LDAP attribute names aren't case sensitive,
        // and the demographic payload may spell them differently than the
        // expression does.
        for (Map.Entry<String, List<String>> e : userAttrs.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(attr)) {
                List<String> values = e.getValue();
                return (values == null || values.isEmpty()) ? "" : safe(values.get(0));
            }
        }
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Shared with {@link IsvaSecUserPlans#generalizedTime}. */
    static String generalizedTime(Instant t) {
        return IsvaSecUserPlans.generalizedTime(t);
    }

    /**
     * Years → seconds using a {@code 365.25}-day year, byte-identical to the
     * arithmetic the pre-model {@code secValidUntil} default used, so a
     * migrated config's expiry lands on the same second.
     */
    static long yearsInSeconds(int years) {
        return Math.round(years * 365.25d * 24d * 3600d);
    }
}
