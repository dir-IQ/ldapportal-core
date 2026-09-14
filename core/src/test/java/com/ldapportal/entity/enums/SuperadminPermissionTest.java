// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SuperadminPermissionTest {

    @Test
    void expand_owner_holdsEverything() {
        assertThat(SuperadminPermission.expand(EnumSet.of(SuperadminPermission.MANAGE_SUPERADMINS)))
                .containsExactlyInAnyOrder(SuperadminPermission.values());
    }

    @Test
    void expand_manageImpliesView() {
        assertThat(SuperadminPermission.expand(EnumSet.of(SuperadminPermission.MANAGE_DIRECTORIES)))
                .containsExactlyInAnyOrder(
                        SuperadminPermission.MANAGE_DIRECTORIES,
                        SuperadminPermission.VIEW_DIRECTORIES);
    }

    @Test
    void expand_viewDoesNotImplyManage() {
        assertThat(SuperadminPermission.expand(EnumSet.of(SuperadminPermission.VIEW_DIRECTORIES)))
                .containsExactly(SuperadminPermission.VIEW_DIRECTORIES);
    }

    @Test
    void expand_emptyStaysEmpty() {
        assertThat(SuperadminPermission.expand(EnumSet.noneOf(SuperadminPermission.class))).isEmpty();
    }

    /** Every VIEW_* key is the implied counterpart of exactly one MANAGE_* key (VIEW_LICENSE has none). */
    @Test
    void everyViewKeyExceptLicense_isImpliedByItsManageKey() {
        for (SuperadminPermission view : SuperadminPermission.values()) {
            if (!view.isViewTier() || view == SuperadminPermission.VIEW_LICENSE) continue;
            String suffix = view.name().substring("VIEW_".length());
            SuperadminPermission manage = SuperadminPermission.valueOf("MANAGE_" + suffix);
            assertThat(manage.implies()).as(manage.name()).isEqualTo(view);
        }
    }

    @Test
    void viewKeysNeverImplyAnything() {
        for (SuperadminPermission p : SuperadminPermission.values()) {
            if (p.isViewTier()) assertThat(p.implies()).as(p.name()).isNull();
        }
    }

    @Test
    void dbValues_areUniqueAndRoundTrip() {
        Set<String> dbValues = Arrays.stream(SuperadminPermission.values())
                .map(SuperadminPermission::getDbValue).collect(Collectors.toSet());
        assertThat(dbValues).hasSize(SuperadminPermission.values().length);
        for (SuperadminPermission p : SuperadminPermission.values()) {
            assertThat(SuperadminPermission.fromDbValue(p.getDbValue())).isEqualTo(p);
            assertThat(p.getDbValue()).startsWith(p.isViewTier() ? "superadmin.view_" : "superadmin.manage_");
        }
    }
}
