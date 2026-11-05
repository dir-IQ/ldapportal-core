// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.MembershipState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncReviewKindTest {

    @Test
    void classifiesTheEnginesReviewReasons() {
        assertThat(SyncReviewKind.of(MembershipState.REVIEW,
                "scope-exit held for review (deletePolicy=REVIEW)")).isEqualTo(SyncReviewKind.PENDING_DELETE);
        assertThat(SyncReviewKind.of(MembershipState.REVIEW,
                "scope-exit held for review (delete guard tripped)")).isEqualTo(SyncReviewKind.PENDING_DELETE);
        assertThat(SyncReviewKind.of(MembershipState.REVIEW,
                "ambiguous: 2 target entries carry sourceAnchor=x")).isEqualTo(SyncReviewKind.AMBIGUOUS_MATCH);
        assertThat(SyncReviewKind.of(MembershipState.REVIEW,
                "unanchored entry already at placement DN uid=a,dc=dst"))
                .isEqualTo(SyncReviewKind.PLACEMENT_CONFLICT);
    }

    @Test
    void unknownOrMissingReasonIsOther() {
        assertThat(SyncReviewKind.of(MembershipState.REVIEW, "something else")).isEqualTo(SyncReviewKind.OTHER);
        assertThat(SyncReviewKind.of(MembershipState.REVIEW, null)).isEqualTo(SyncReviewKind.OTHER);
    }

    @Test
    void rowsNotInReviewHaveNoKind() {
        assertThat(SyncReviewKind.of(MembershipState.FAILED,
                "scope-exit held for review (deletePolicy=REVIEW)")).isNull();
        assertThat(SyncReviewKind.of(MembershipState.APPLIED, null)).isNull();
    }
}
