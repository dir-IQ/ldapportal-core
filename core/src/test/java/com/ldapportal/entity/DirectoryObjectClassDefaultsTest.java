// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.converter.ObjectClassListConverter;
import com.ldapportal.entity.enums.DirectoryType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Vendor defaults, effective resolution, and filter shapes. */
class DirectoryObjectClassDefaultsTest {

    @Test
    void vendorDefaults_areNonEmptyForEveryType() {
        for (DirectoryType t : DirectoryType.values()) {
            assertThat(DirectoryObjectClassDefaults.userObjectClasses(t)).isNotEmpty();
            assertThat(DirectoryObjectClassDefaults.groupObjectClasses(t)).isNotEmpty();
        }
    }

    @Test
    void activeDirectory_usesAdSpellings() {
        assertThat(DirectoryObjectClassDefaults.userObjectClasses(DirectoryType.ACTIVE_DIRECTORY))
                .containsExactly("user");
        assertThat(DirectoryObjectClassDefaults.groupObjectClasses(DirectoryType.ACTIVE_DIRECTORY))
                .containsExactly("group");
    }

    @Test
    void configuredValue_winsOverVendorDefault() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        dc.setUserObjectClasses(List.of("customPerson"));
        assertThat(DirectoryObjectClassDefaults.effectiveUserObjectClasses(dc))
                .containsExactly("customPerson");
    }

    @Test
    void emptyConfigured_fallsBackToVendorDefault() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        dc.setUserObjectClasses(List.of());
        assertThat(DirectoryObjectClassDefaults.effectiveUserObjectClasses(dc))
                .isEqualTo(DirectoryObjectClassDefaults.userObjectClasses(DirectoryType.OPENLDAP));
    }

    @Test
    void effectiveSet_isLowercasedForMatching() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.GENERIC);
        dc.setGroupObjectClasses(List.of("GroupOfNames"));
        assertThat(DirectoryObjectClassDefaults.effectiveGroupObjectClassSet(dc))
                .containsExactly("groupofnames");
    }

    @Test
    void orFilter_singleClass_hasNoOrWrapper() {
        assertThat(DirectoryObjectClassDefaults.orFilter(List.of("group")))
                .isEqualTo("(objectClass=group)");
    }

    @Test
    void orFilter_multipleClasses_buildsOr() {
        assertThat(DirectoryObjectClassDefaults.orFilter(List.of("groupOfNames", "posixGroup")))
                .isEqualTo("(|(objectClass=groupOfNames)(objectClass=posixGroup))");
    }

    @Test
    void userSearchFilter_excludesComputerWhenAdUserPresent() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.ACTIVE_DIRECTORY);
        assertThat(DirectoryObjectClassDefaults.userSearchFilter(dc))
                .isEqualTo("(&(objectClass=user)(!(objectClass=computer)))");
    }

    @Test
    void userSearchFilter_noComputerExclusionForNonAd() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        assertThat(DirectoryObjectClassDefaults.userSearchFilter(dc)).doesNotContain("computer");
    }

    @Test
    void converter_roundTrips_andCollapsesEmptyToNull() {
        ObjectClassListConverter c = new ObjectClassListConverter();
        assertThat(c.convertToDatabaseColumn(List.of("inetOrgPerson", "person")))
                .isEqualTo("inetOrgPerson,person");
        assertThat(c.convertToDatabaseColumn(List.of())).isNull();
        assertThat(c.convertToEntityAttribute(" inetOrgPerson , person "))
                .containsExactly("inetOrgPerson", "person");
        assertThat(c.convertToEntityAttribute(null)).isEmpty();
    }
}
