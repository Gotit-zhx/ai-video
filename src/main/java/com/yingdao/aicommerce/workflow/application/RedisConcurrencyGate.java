package com.yingdao.aicommerce.workflow.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class RedisConcurrencyGate {

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>("""
                    redis.call('zremrangebyscore', KEYS[1], '-inf', ARGV[1])
                    if redis.call('zcard', KEYS[1]) < tonumber(ARGV[2]) then
                      redis.call('zadd', KEYS[1], ARGV[3], ARGV[4])
                      redis.call('pexpire', KEYS[1], ARGV[5])
                      return 1
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int maxActiveTasks;
    private final int maxModelCalls;
    private final Duration permitLease;

    public RedisConcurrencyGate(
            StringRedisTemplate redisTemplate,
            @Value("${app.dispatch.max-active-tasks:80}") int maxActiveTasks,
            @Value("${app.dispatch.max-model-calls:20}") int maxModelCalls,
            @Value("${app.dispatch.permit-lease:30m}") Duration permitLease
    ) {
        this.redisTemplate = redisTemplate;
        this.maxActiveTasks = maxActiveTasks;
        this.maxModelCalls = maxModelCalls;
        this.permitLease = permitLease;
    }

    public Permit tryAcquireTask() {
        return tryAcquire("tasks", maxActiveTasks);
    }

    public Permit tryAcquireModelCall() {
        return tryAcquire("models", maxModelCalls);
    }

    private Permit tryAcquire(String scope, int limit) {
        long now = Instant.now().toEpochMilli();
        long expiresAt = now + permitLease.toMillis();
        String token = UUID.randomUUID().toString();
        String key = "heyi:capacity:" + scope;
        Long acquired = redisTemplate.execute(
                ACQUIRE_SCRIPT,
                List.of(key),
                String.valueOf(now),
                String.valueOf(limit),
                String.valueOf(expiresAt),
                token,
                String.valueOf(permitLease.plusMinutes(1).toMillis())
        );
        return Long.valueOf(1L).equals(acquired)
                ? new Permit(redisTemplate, key, token)
                : null;
    }

    public static final class Permit implements AutoCloseable {
        private final StringRedisTemplate redisTemplate;
        private final String key;
        private final String token;
        private boolean closed;

        private Permit(StringRedisTemplate redisTemplate, String key, String token) {
            this.redisTemplate = redisTemplate;
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            if (!closed) {
                redisTemplate.opsForZSet().remove(key, token);
                closed = true;
            }
        }
    }
}
