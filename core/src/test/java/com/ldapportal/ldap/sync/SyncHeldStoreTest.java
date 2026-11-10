// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncHeldReference;
import com.ldapportal.entity.enums.SyncHeldStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SyncHeldStore}'s SQL against the test database. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(SyncHeldStore.class)
class SyncHeldStoreTest {

    @Autowired private SyncHeldStore store;

    private static SyncHeldReference ref(String member, String reference, SyncHeldStatus status) {
        return new SyncHeldReference("cn=" + member + ",ou=groups,dc=t", "cn=" + member + ",ou=groups,dc=s",
                member, "member", reference, status);
    }

    @Test
    void replace_storesEveryReference_inOrder_andCountsThemPerStatus() {
        UUID set = UUID.randomUUID();
        store.replace(set, List.of(
                ref("eng", "uid=ghost,dc=s", SyncHeldStatus.DANGLING),
                ref("eng", "uid=new,dc=s", SyncHeldStatus.UNSYNCED),
                ref("ops", "uid=far,dc=s", SyncHeldStatus.UNCHECKED)));

        var rows = store.page(set, null, null, 0, 10);
        assertThat(rows).extracting(SyncHeldStore.Row::seq).containsExactly(0, 1, 2);
        assertThat(rows.get(0).ref()).isEqualTo(ref("eng", "uid=ghost,dc=s", SyncHeldStatus.DANGLING));
        assertThat(store.countsByStatus(set)).containsEntry(SyncHeldStatus.DANGLING, 1L)
                .containsEntry(SyncHeldStatus.UNSYNCED, 1L).containsEntry(SyncHeldStatus.UNCHECKED, 1L);
    }

    @Test
    void page_filtersByStatusAndSearch_andReplaceDropsTheOldList() {
        UUID set = UUID.randomUUID();
        store.replace(set, List.of(
                ref("eng", "uid=ghost,dc=s", SyncHeldStatus.DANGLING),
                ref("ops", "uid=Gone_1,dc=s", SyncHeldStatus.DANGLING),
                ref("ops", "uid=new,dc=s", SyncHeldStatus.UNSYNCED)));

        assertThat(store.count(set, SyncHeldStatus.DANGLING, null)).isEqualTo(2);
        // Case-insensitive, and LIKE wildcards in the search are literal.
        assertThat(store.page(set, SyncHeldStatus.DANGLING, "gone_", 0, 10))
                .extracting(r -> r.ref().reference()).containsExactly("uid=Gone_1,dc=s");
        assertThat(store.count(set, null, "cn=ops")).isEqualTo(2);

        store.replace(set, List.of(ref("hr", "uid=x,dc=s", SyncHeldStatus.UNSYNCED)));
        assertThat(store.count(set, null, null)).isEqualTo(1);
        assertThat(store.countsByStatus(set)).containsEntry(SyncHeldStatus.DANGLING, 0L);
    }

    @Test
    void replace_writesLargeListsInBatches_andForEachStreamsThemAll() {
        UUID set = UUID.randomUUID();
        List<SyncHeldReference> many = new ArrayList<>();
        for (int i = 0; i < 2_500; i++) {
            many.add(ref("big", "uid=u" + i + ",dc=s", SyncHeldStatus.UNSYNCED));
        }
        store.replace(set, many);

        List<SyncHeldStore.Row> seen = new ArrayList<>();
        store.forEach(set, seen::add);
        assertThat(seen).hasSize(2_500);
        assertThat(seen.get(2_499).seq()).isEqualTo(2_499);
    }
}
