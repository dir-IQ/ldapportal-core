// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Deleting children before parents, and finding the sync's entries beneath a DN. */
class SyncSubtreeDeleteOrderTest {

    @Test
    void depth_countsRdns_includingEscapedCommas_andMinusOneForNone() {
        assertThat(SyncDnUtil.depth("uid=a,ou=team,ou=org,dc=x")).isEqualTo(4);
        assertThat(SyncDnUtil.depth("cn=Smith\\, Jo,ou=people,dc=x")).isEqualTo(3);
        assertThat(SyncDnUtil.depth(null)).isEqualTo(-1);
        assertThat(SyncDnUtil.depth(" ")).isEqualTo(-1);
    }

    @Test
    void deepestFirst_putsChildrenBeforeParents_stably_andEntriesWithoutADnLast() {
        List<String> dns = new ArrayList<>(Arrays.asList(
                "ou=team,ou=org,dc=x", null, "uid=a,ou=team,ou=org,dc=x", "ou=other,ou=org,dc=x",
                "uid=b,ou=team,ou=org,dc=x"));
        dns.sort(SyncDnUtil.deepestFirst(Function.identity()));
        assertThat(dns).containsExactly("uid=a,ou=team,ou=org,dc=x", "uid=b,ou=team,ou=org,dc=x",
                "ou=team,ou=org,dc=x", "ou=other,ou=org,dc=x", null);
    }

    @Test
    void underDnPattern_matchesOnlyEntriesBeneath_withLikeWildcardsEscaped() {
        assertThat(RecomputeEngine.underDnPattern(" OU=Team_1,DC=x ")).isEqualTo("%,ou=team\\_1,dc=x");
        assertThat(RecomputeEngine.underDnPattern("ou=100%,dc=x")).isEqualTo("%,ou=100\\%,dc=x");
        assertThat(RecomputeEngine.underDnPattern("cn=a\\,b,dc=x")).isEqualTo("%,cn=a\\\\,b,dc=x");
    }
}
