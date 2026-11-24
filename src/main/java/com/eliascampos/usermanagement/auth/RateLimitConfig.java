package com.eliascampos.usermanagement.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;

/** Escolhe o store dos limiters: Redis quando habilitado, memoria por padrao. */
@Configuration
public class RateLimitConfig {

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.redis-enabled", havingValue = "true")
    LettuceConnectionFactory rateLimitRedisConnectionFactory(@Value("${app.rate-limit.redis-uri:}") String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("app.rate-limit.redis-uri e obrigatorio quando redis-enabled=true");
        }
        URI parsed = URI.create(uri);
        int port = parsed.getPort() == -1 ? 6379 : parsed.getPort();
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(parsed.getHost(), port);
        String userInfo = parsed.getUserInfo();
        if (userInfo != null && userInfo.contains(":")) {
            config.setPassword(userInfo.substring(userInfo.indexOf(':') + 1));
        }
        if (parsed.getPath() != null && parsed.getPath().length() > 1) {
            config.setDatabase(Integer.parseInt(parsed.getPath().substring(1)));
        }
        return new LettuceConnectionFactory(config);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.redis-enabled", havingValue = "true")
    RateLimitStore redisRateLimitStore(StringRedisTemplate redis) {
        return new RedisRateLimitStore(redis);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.redis-enabled", havingValue = "false", matchIfMissing = true)
    RateLimitStore localRateLimitStore() {
        return new LocalRateLimitStore();
    }
}
