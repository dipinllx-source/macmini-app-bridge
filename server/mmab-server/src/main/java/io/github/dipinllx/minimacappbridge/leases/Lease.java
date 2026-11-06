package io.github.dipinllx.minimacappbridge.leases;

import java.time.Instant;

/**
 * 独占租约（设计 §9.1）：每实例同一时刻最多一个 ACTIVE；fencingToken 每次签发单调递增，
 * 绑定签发时的 generation。
 */
public record Lease(String leaseId, String instanceId, String holderSessionId, long fencingToken, long generation,
        Instant acquiredAt, Instant expiresAt) {

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    Lease renewedUntil(Instant newExpiry) {
        return new Lease(leaseId, instanceId, holderSessionId, fencingToken, generation, acquiredAt, newExpiry);
    }
}
