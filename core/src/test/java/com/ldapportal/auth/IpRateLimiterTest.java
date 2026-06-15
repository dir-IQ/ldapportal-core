// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class IpRateLimiterTest {

    private IpRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new IpRateLimiter();
    }

    @Test
    void check_underLimit_doesNotThrow() {
        for (int i = 0; i < 10; i++) {
            limiter.check("192.168.1.1");
        }
        // 10 calls should all succeed
    }

    @Test
    void check_overLimit_throwsTooManyRequests() {
        for (int i = 0; i < 10; i++) {
            limiter.check("10.0.0.1");
        }
        assertThatThrownBy(() -> limiter.check("10.0.0.1"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void check_differentIps_trackedIndependently() {
        for (int i = 0; i < 10; i++) {
            limiter.check("10.0.0.1");
        }
        // Different IP should still be allowed
        assertThatCode(() -> limiter.check("10.0.0.2")).doesNotThrowAnyException();
    }
}
