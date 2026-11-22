package com.eliascampos.usermanagement.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetRateLimiterTest {

    private PasswordResetRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new PasswordResetRateLimiter();
    }

    @Test
    void blocksSixthRequestForSameEmailAndIp() {
        for (int i = 0; i < PasswordResetRateLimiter.MAX_PER_EMAIL_AND_IP; i++) {
            rateLimiter.acquire("maria@email.com", "127.0.0.1");
        }

        assertThatThrownBy(() -> rateLimiter.acquire("maria@email.com", "127.0.0.1"))
                .isInstanceOf(TooManyPasswordResetRequestsException.class)
                .satisfies(ex -> assertThat(((TooManyPasswordResetRequestsException) ex)
                        .getRetryAfterSeconds()).isPositive());
    }

    @Test
    void countsUnknownEmails() {
        for (int i = 0; i < PasswordResetRateLimiter.MAX_PER_EMAIL_AND_IP; i++) {
            rateLimiter.acquire("naoexiste@email.com", "127.0.0.1");
        }

        assertThatThrownBy(() -> rateLimiter.acquire("naoexiste@email.com", "127.0.0.1"))
                .isInstanceOf(TooManyPasswordResetRequestsException.class);
    }

    @Test
    void blocksByIpAcrossDifferentEmails() {
        for (int i = 0; i < PasswordResetRateLimiter.MAX_PER_IP; i++) {
            rateLimiter.acquire("user" + i + "@email.com", "127.0.0.1");
        }

        assertThatThrownBy(() -> rateLimiter.acquire("user20@email.com", "127.0.0.1"))
                .isInstanceOf(TooManyPasswordResetRequestsException.class);
    }

    @Test
    void clearResetsState() {
        for (int i = 0; i < PasswordResetRateLimiter.MAX_PER_EMAIL_AND_IP; i++) {
            rateLimiter.acquire("maria@email.com", "127.0.0.1");
        }
        assertThatThrownBy(() -> rateLimiter.acquire("maria@email.com", "127.0.0.1"))
                .isInstanceOf(TooManyPasswordResetRequestsException.class);

        rateLimiter.clear();

        assertThatCode(() -> rateLimiter.acquire("maria@email.com", "127.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void limitsAreSeparatePerIp() {
        for (int i = 0; i < PasswordResetRateLimiter.MAX_PER_EMAIL_AND_IP; i++) {
            rateLimiter.acquire("maria@email.com", "10.0.0.1");
        }

        assertThatCode(() -> rateLimiter.acquire("maria@email.com", "10.0.0.2"))
                .doesNotThrowAnyException();
    }
}
