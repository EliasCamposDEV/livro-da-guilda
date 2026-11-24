package com.eliascampos.usermanagement.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    @Test
    void lockIsStableAcrossFailuresAndReset() {
        LoginRateLimiter rateLimiter = new LoginRateLimiter(new LocalRateLimitStore());
        String account = "maria@email.com";
        Object before = rateLimiter.lock(account);

        rateLimiter.recordFailure(account + "@127.0.0.1");
        rateLimiter.reset(account + "@127.0.0.1");

        assertThat(rateLimiter.lock(account)).isSameAs(before);
    }
}
