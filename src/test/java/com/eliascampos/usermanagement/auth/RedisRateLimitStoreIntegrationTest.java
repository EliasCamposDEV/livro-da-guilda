package com.eliascampos.usermanagement.auth;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisRateLimitStoreIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void countersAreSharedBetweenInstances() {
        LettuceConnectionFactory firstFactory = connect();
        LettuceConnectionFactory secondFactory = connect();
        try {
            StringRedisTemplate firstTemplate = template(firstFactory);
            StringRedisTemplate secondTemplate = template(secondFactory);
            RedisRateLimitStore first = new RedisRateLimitStore(firstTemplate);
            RedisRateLimitStore second = new RedisRateLimitStore(secondTemplate);
            Duration window = Duration.ofMinutes(1);

            first.increment("chave", window);
            first.increment("chave", window);

            assertThat(second.get("chave", window).count()).isEqualTo(2);
            assertThat(second.get("chave", window).retryAfterSeconds()).isPositive();

            second.reset("chave");
            assertThat(first.get("chave", window).count()).isZero();
        } finally {
            firstFactory.destroy();
            secondFactory.destroy();
        }
    }

    private static LettuceConnectionFactory connect() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        return factory;
    }

    private static StringRedisTemplate template(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
