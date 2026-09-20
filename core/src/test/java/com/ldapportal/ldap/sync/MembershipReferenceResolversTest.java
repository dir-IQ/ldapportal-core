// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The reference resolver prefers the referrer's own link, falls back to the
 * consensus of other links (the IVIA secDN case: c=admin → c=us mirrored by
 * separate links), and drops an ambiguous cross-link reference.
 */
@ExtendWith(MockitoExtension.class)
class MembershipReferenceResolversTest {

    private static final String SRC_DN = "uid=alice,ou=people,c=us";

    @Mock private SyncSetRepository syncSetRepo;
    @Mock private MembershipRepository membershipRepo;
    @InjectMocks private MembershipReferenceResolvers resolvers;

    private SyncLink link;
    private UUID ownSetId;
    private UUID otherSetId;
    private UUID thirdSetId;

    @BeforeEach
    void setUp() {
        link = new SyncLink();
        link.setId(UUID.randomUUID());
        ownSetId = UUID.randomUUID();
        otherSetId = UUID.randomUUID();
        thirdSetId = UUID.randomUUID();
        SyncSet own = new SyncSet();
        own.setId(ownSetId);
        own.setLinkId(link.getId());
        when(syncSetRepo.findAllByLinkId(link.getId())).thenReturn(List.of(own));
    }

    @Test
    void unsyncedReferent_resolvesEmpty() {
        when(membershipRepo.findAllBySourceDn(SyncDnUtil.normalize(SRC_DN))).thenReturn(List.of());

        assertThat(resolvers.forLink(link).resolveTargetDn(SRC_DN)).isEmpty();
    }

    @Test
    void sameLinkRow_winsOverOtherLinks() {
        when(membershipRepo.findAllBySourceDn(SyncDnUtil.normalize(SRC_DN))).thenReturn(List.of(
                row(otherSetId, "uid=alice,ou=elsewhere,c=us"),
                row(ownSetId, "uid=alice,ou=users,c=us")));

        assertThat(resolvers.forLink(link).resolveTargetDn(SRC_DN)).contains("uid=alice,ou=users,c=us");
    }

    @Test
    void otherLinkRow_resolvesAcrossLinks() {
        when(membershipRepo.findAllBySourceDn(SyncDnUtil.normalize(SRC_DN))).thenReturn(List.of(
                row(otherSetId, "uid=alice,ou=people,c=us")));

        assertThat(resolvers.forLink(link).resolveTargetDn(SRC_DN)).contains("uid=alice,ou=people,c=us");
    }

    @Test
    void otherLinksAgreeingOnTargetDn_resolve() {
        when(membershipRepo.findAllBySourceDn(SyncDnUtil.normalize(SRC_DN))).thenReturn(List.of(
                row(otherSetId, "uid=alice,ou=people,c=us"),
                row(thirdSetId, "UID=alice, OU=people, C=us")));

        assertThat(resolvers.forLink(link).resolveTargetDn(SRC_DN)).contains("uid=alice,ou=people,c=us");
    }

    @Test
    void otherLinksDisagreeing_dropAsAmbiguous() {
        when(membershipRepo.findAllBySourceDn(SyncDnUtil.normalize(SRC_DN))).thenReturn(List.of(
                row(otherSetId, "uid=alice,ou=people,c=us"),
                row(thirdSetId, "uid=alice,ou=people,dc=dev")));

        assertThat(resolvers.forLink(link).resolveTargetDn(SRC_DN)).isEmpty();
    }

    @Test
    void rowsWithoutTargetDn_areIgnored() {
        when(membershipRepo.findAllBySourceDn(SyncDnUtil.normalize(SRC_DN))).thenReturn(List.of(
                row(ownSetId, null),
                row(otherSetId, "uid=alice,ou=people,c=us")));

        assertThat(resolvers.forLink(link).resolveTargetDn(SRC_DN)).contains("uid=alice,ou=people,c=us");
    }

    @Test
    void rowsNotApplied_areIgnored_soReferencesNeverPointAtAbsentTargets() {
        Membership failed = row(ownSetId, "uid=alice,ou=users,c=us");
        failed.setState(MembershipState.FAILED);
        Membership review = row(otherSetId, "uid=alice,ou=people,c=us");
        review.setState(MembershipState.REVIEW);
        when(membershipRepo.findAllBySourceDn(SyncDnUtil.normalize(SRC_DN))).thenReturn(List.of(failed, review));

        assertThat(resolvers.forLink(link).resolveTargetDn(SRC_DN)).isEmpty();
    }

    private static Membership row(UUID setId, String targetDn) {
        Membership m = new Membership();
        m.setSyncSetId(setId);
        m.setIdentity(UUID.randomUUID().toString());
        m.setSourceDn(SyncDnUtil.normalize(SRC_DN));
        m.setTargetDn(targetDn);
        m.setState(MembershipState.APPLIED);
        return m;
    }
}
