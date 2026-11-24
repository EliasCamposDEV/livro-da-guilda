package com.eliascampos.usermanagement.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Contadores em Redis, compartilhados por todas as instancias da API. */
class RedisRateLimitStore implements RateLimitStore {

    private static final String PREFIX = "rate:";

    // INCR e TTL no mesmo script: a primeira tentativa define a janela sem corrida.
    private static final RedisScript<String> INCREMENT = RedisScript.of(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return tostring(n)",
            String.class);

    private final StringRedisTemplate redis;

    RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public State get(String key, Duration window) {
        String value = redis.opsForValue().get(PREFIX + key);
        if (value == null) {
            return new State(0, 0);
        }
        return new State(Integer.parseInt(value), ttlSeconds(key));
    }

    @Override
    public State increment(String key, Duration window) {
        String count = redis.execute(INCREMENT, List.of(PREFIX + key), String.valueOf(window.toMillis()));
        return new State(count == null ? 0 : Integer.parseInt(count), ttlSeconds(key));
    }

    @Override
    public void reset(String key) {
        redis.delete(PREFIX + key);
    }

    @Override
    public void clear() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private long ttlSeconds(String key) {
        Long ttl = redis.getExpire(PREFIX + key, TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? 0 : ttl;
    }
}
