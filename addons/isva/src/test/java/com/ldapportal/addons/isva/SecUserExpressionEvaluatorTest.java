// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecUserExpressionEvaluatorTest {

    private static final Instant NOW = Instant.parse("2020-01-02T03:04:05Z");
    private static final Map<String, List<String>> USER = Map.of(
            "uid", List.of("jdoe"),
            "cn", List.of("Jane Doe"));
    private static final UnaryOperator<String> NO_SEC = name -> {
        throw new IllegalStateException("no sec ref expected: " + name);
    };

    private static String eval(String expr, UnaryOperator<String> secRef) {
        return SecUserExpressionEvaluator.evaluate(expr, USER, secRef, NOW);
    }

    @Test
    void uuidFunction_returnsRandomUuid() {
        String a = eval("uuid()", NO_SEC);
        String b = eval("uuid()", NO_SEC);
        assertThat(a).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(a).isNotEqualTo(b); // fresh each call
    }

    @Test
    void nowFunction_returnsGeneralizedTime() {
        assertThat(eval("now()", NO_SEC)).isEqualTo("20200102030405Z");
    }

    @Test
    void nowPlusYearsFunction_addsWholeYears_365_25dayArithmetic() {
        // Same arithmetic the pre-model secValidUntil default used.
        long seconds = Math.round(1 * 365.25d * 24d * 3600d);
        String expected = IsvaSecUserPlans.generalizedTime(NOW.plusSeconds(seconds));
        assertThat(eval("nowPlusYears(1)", NO_SEC)).isEqualTo(expected);
        // Surrounding whitespace tolerated.
        assertThat(eval("  nowPlusYears( 1 ) ", NO_SEC)).isEqualTo(expected);
    }

    @Test
    void userReference_resolvesFirstValue_caseInsensitive() {
        assertThat(eval("${user.uid}", NO_SEC)).isEqualTo("jdoe");
        assertThat(eval("${user.UID}", NO_SEC)).isEqualTo("jdoe");
    }

    @Test
    void userReference_missingAttribute_resolvesEmpty() {
        assertThat(eval("${user.mail}", NO_SEC)).isEmpty();
    }

    @Test
    void template_mixesLiteralAndReferences_percentIsLiteral() {
        // secDomainId shape — the % is literal, ${sec.*} comes from the resolver.
        String result = eval("${sec.secAuthority}%${user.uid}",
                name -> "secAuthority".equalsIgnoreCase(name) ? "Default" : "");
        assertThat(result).isEqualTo("Default%jdoe");
    }

    @Test
    void secReference_delegatesToResolver() {
        assertThat(eval("${sec.secAuthority}", name -> "EUR")).isEqualTo("EUR");
    }

    @Test
    void nullExpression_resolvesEmpty() {
        assertThat(SecUserExpressionEvaluator.evaluate(null, USER, NO_SEC, NOW)).isEmpty();
    }

    @Test
    void functionDetectionWinsOverTemplate() {
        // A bare function is never treated as literal text.
        assertThat(eval("uuid()", NO_SEC)).doesNotContain("uuid");
    }

    @Test
    void unknownFunctionLikeText_treatedAsLiteralTemplate() {
        // Not one of the three known functions → passes through as literal text
        // (no ${} refs to interpolate).
        assertThat(eval("frobnicate()", NO_SEC)).isEqualTo("frobnicate()");
    }
}
