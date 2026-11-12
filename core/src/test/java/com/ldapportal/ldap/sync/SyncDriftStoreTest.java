// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.sync.SyncDriftItem;
import com.ldapportal.entity.enums.SyncDriftCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SyncDriftStore}'s SQL against the test database. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({SyncDriftStore.class, SyncDriftStoreTest.Json.class})
class SyncDriftStoreTest {

    @TestConfiguration
    static class Json {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private SyncDriftStore store;

    private static SyncDriftItem missing(String uid) {
        return new SyncDriftItem(SyncDriftCategory.MISSING, "uid=" + uid + ",ou=people,dc=t",
                "uid=" + uid + ",ou=staff,dc=s", uid, List.of());
    }

    private static SyncDriftItem orphan(String uid) {
        return new SyncDriftItem(SyncDriftCategory.ORPHAN, "uid=" + uid + ",ou=people,dc=t", null, null, List.of());
    }

    private static SyncDriftItem mismatch(String uid, String title) {
        return new SyncDriftItem(SyncDriftCategory.MISMATCH, "uid=" + uid + ",ou=people,dc=t",
                "uid=" + uid + ",ou=staff,dc=s", uid, List.of(new SyncDriftItem.AttributeDiff("title",
                List.of(title), List.of("Engineer"), false, false)));
    }

    @Test
    void replace_storesEveryItem_inOrder_withTheirAttributes() {
        UUID set = UUID.randomUUID();
        store.replace(set, List.of(missing("pn"), orphan("old"), mismatch("jo", "Lead")));

        var rows = store.page(set, null, null, 0, 10);

        assertThat(rows).extracting(SyncDriftStore.Row::seq).containsExactly(0, 1, 2);
        assertThat(rows.get(0).item()).isEqualTo(missing("pn"));
        assertThat(rows.get(1).item().sourceDn()).isNull();
        assertThat(rows.get(2).item().attributes()).singleElement().satisfies(a -> {
            assertThat(a.attribute()).isEqualTo("title");
            assertThat(a.expected()).containsExactly("Lead");
            assertThat(a.actual()).containsExactly("Engineer");
        });
    }

    @Test
    void storesWhichValuesOnlyOneSideHas_andReadsOlderRowsWithout() throws Exception {
        UUID set = UUID.randomUUID();
        store.replace(set, List.of(new SyncDriftItem(SyncDriftCategory.MISMATCH, "cn=g,dc=t", "cn=g,dc=s", "g",
                List.of(new SyncDriftItem.AttributeDiff("member", List.of("uid=b,dc=t", "uid=a,dc=t"),
                        List.of("uid=c,dc=t", "uid=a,dc=t"), false, false, List.of(0), List.of(0))))));

        var a = store.page(set, null, null, 0, 10).get(0).item().attributes().get(0);
        assertThat(a.expectedOnly()).containsExactly(0);
        assertThat(a.actualOnly()).containsExactly(0);

        // A row stored before the flags were recorded has none.
        var legacy = new ObjectMapper().readValue(
                "{\"attribute\":\"title\",\"expected\":[\"Lead\"],\"actual\":[\"Eng\"],\"hidden\":false,\"truncated\":false}",
                SyncDriftItem.AttributeDiff.class);
        assertThat(legacy.expected()).containsExactly("Lead");
        assertThat(legacy.expectedOnly()).isNull();
        assertThat(legacy.actualOnly()).isNull();
    }

    @Test
    void replace_keepsOnlyTheLatestVerify_perSet() {
        UUID set = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        store.replace(set, List.of(missing("a"), missing("b")));
        store.replace(other, List.of(orphan("x")));

        store.replace(set, List.of(orphan("c")));

        assertThat(store.page(set, null, null, 0, 10)).extracting(r -> r.item().targetDn())
                .containsExactly("uid=c,ou=people,dc=t");
        assertThat(store.count(other, null, null)).isEqualTo(1);
        store.replace(set, List.of());
        assertThat(store.count(set, null, null)).isZero();
    }

    @Test
    void page_andCount_filterByCategory_andSearchDnsAndAttributes_ignoringCase() {
        UUID set = UUID.randomUUID();
        store.replace(set, List.of(missing("pnair"), orphan("old.temp"), mismatch("jsmith", "Senior Lead"),
                mismatch("akim", "Manager")));

        assertThat(store.count(set, SyncDriftCategory.MISMATCH, null)).isEqualTo(2);
        assertThat(store.page(set, SyncDriftCategory.MISMATCH, null, 1, 10))
                .extracting(r -> r.item().identity()).containsExactly("akim");
        // Target DN, source DN, and an attribute's values are all searched.
        assertThat(store.count(set, null, "OLD.TEMP")).isEqualTo(1);
        assertThat(store.count(set, null, "ou=staff")).isEqualTo(3);
        assertThat(store.page(set, null, "senior", 0, 10)).extracting(r -> r.item().identity())
                .containsExactly("jsmith");
        // LIKE wildcards in the search are literal.
        assertThat(store.count(set, null, "%")).isZero();
    }

    @Test
    void countsByCategory_coversEveryCategory() {
        UUID set = UUID.randomUUID();
        store.replace(set, List.of(missing("a"), missing("b"), mismatch("c", "x")));

        assertThat(store.countsByCategory(set)).containsEntry(SyncDriftCategory.MISSING, 2L)
                .containsEntry(SyncDriftCategory.ORPHAN, 0L).containsEntry(SyncDriftCategory.MISMATCH, 1L);
    }

    @Test
    void replace_writesInBatches_andForEachStreamsThemAllInOrder() {
        UUID set = UUID.randomUUID();
        List<SyncDriftItem> many = new ArrayList<>();
        for (int i = 0; i < 2500; i++) many.add(missing("u" + i));
        store.replace(set, many);

        List<Integer> seqs = new ArrayList<>();
        store.forEach(set, r -> seqs.add(r.seq()));

        assertThat(seqs).hasSize(2500);
        assertThat(seqs.get(0)).isZero();
        assertThat(seqs.get(2499)).isEqualTo(2499);
    }

    @Test
    void bySeq_andDelete_actOnTheGivenPositionsOnly() {
        UUID set = UUID.randomUUID();
        store.replace(set, List.of(missing("a"), orphan("b"), mismatch("c", "x")));

        assertThat(store.bySeq(set, List.of(2, 0, 9))).extracting(SyncDriftStore.Row::seq).containsExactly(0, 2);
        assertThat(store.bySeq(set, List.of())).isEmpty();

        store.delete(set, store.bySeq(set, List.of(0, 2)));

        assertThat(store.page(set, null, null, 0, 10)).extracting(SyncDriftStore.Row::seq).containsExactly(1);
    }

    @Test
    void delete_leavesANewerVerifysRowsAtTheSamePositionsAlone() {
        UUID set = UUID.randomUUID();
        store.replace(set, List.of(orphan("a"), orphan("b")));
        var chosen = store.bySeq(set, List.of(0));

        // A newer verify renumbers: position 0 is now another entry.
        store.replace(set, List.of(orphan("z"), orphan("a")));
        store.delete(set, chosen);

        assertThat(store.page(set, null, null, 0, 10)).extracting(r -> r.item().targetDn())
                .containsExactly(orphan("z").targetDn(), orphan("a").targetDn());
    }

    @Test
    void byTargetDn_findsTheStoredItems_caseInsensitively() {
        UUID set = UUID.randomUUID();
        store.replace(set, List.of(missing("pn"), orphan("old"), mismatch("jo", "Lead")));

        assertThat(store.byTargetDn(set, List.of("UID=JO,ou=people,dc=t", "uid=nobody,ou=people,dc=t")))
                .extracting(SyncDriftStore.Row::seq).containsExactly(2);
        assertThat(store.byTargetDn(set, List.of())).isEmpty();
        assertThat(store.byTargetDn(UUID.randomUUID(), List.of("uid=jo,ou=people,dc=t"))).isEmpty();
    }
}
