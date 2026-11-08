package io.github.dipinllx.macminiappbridge.leases;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** 独占租约 + generation fencing（设计 §9.1）。 */
class LeaseManagerTest {

    private final MmabClock clock = TestSupport.clock();
    private final TestSupport.RecordingPublisher events = new TestSupport.RecordingPublisher();
    private final LeaseManager leases = new LeaseManager(clock, TestSupport.properties(), events);

    @Test
    void concurrentAcquireHasExactlyOneWinner() throws Exception {
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LeaseManager.AcquireResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String session = "cs-" + i;
            results.add(pool.submit(() -> {
                start.await();
                return leases.acquire("qwen-01", session, 1);
            }));
        }
        start.countDown();
        int winners = 0;
        int occupied = 0;
        for (Future<LeaseManager.AcquireResult> f : results) {
            LeaseManager.AcquireResult r = f.get();
            if (r.ok()) {
                winners++;
            } else if (r.rejection() == ErrorCode.LEASE_OCCUPIED) {
                occupied++;
            }
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
        assertThat(occupied).isEqualTo(threads - 1);
        assertThat(leases.current("qwen-01")).get().extracting(Lease::fencingToken).isEqualTo(1L);
    }

    @Test
    void sameSessionReusesNoImplicitTakeover() {
        Lease a = leases.acquire("qwen-01", "cs-a", 1).lease();
        LeaseManager.AcquireResult again = leases.acquire("qwen-01", "cs-a", 1);
        assertThat(again.reused()).isTrue();
        assertThat(again.lease().leaseId()).isEqualTo(a.leaseId());
        assertThat(leases.acquire("qwen-01", "cs-b", 1).rejection()).isEqualTo(ErrorCode.LEASE_OCCUPIED);
        assertThat(leases.release(a.leaseId(), "cs-b", ErrorCode.LEASE_REQUIRED)).as("only holder may release").isFalse();
    }

    @Test
    void fencingTokensAreMonotonicAndOldFencesAreStale() {
        Lease first = leases.acquire("qwen-01", "cs-a", 1).lease();
        assertThat(leases.release(first.leaseId(), "cs-a", ErrorCode.LEASE_REQUIRED)).isTrue();
        Lease second = leases.acquire("qwen-01", "cs-b", 1).lease();
        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
        assertThat(leases.isCurrent("qwen-01", first.leaseId(), first.fencingToken(), 1)).isFalse();
        assertThat(leases.isCurrent("qwen-01", second.leaseId(), second.fencingToken(), 1)).isTrue();
        assertThat(leases.isCurrent("qwen-01", second.leaseId(), first.fencingToken(), 1)).isFalse();
        assertThat(leases.isCurrent("qwen-01", second.leaseId(), second.fencingToken(), 2)).as("other generation")
                .isFalse();
        assertThat(leases.heartbeat(first.leaseId(), "cs-a", first.fencingToken()))
                .contains(ErrorCode.STALE_GENERATION);
        assertThat(leases.heartbeat(second.leaseId(), "cs-b", first.fencingToken()))
                .contains(ErrorCode.STALE_GENERATION);
        assertThat(leases.heartbeat(second.leaseId(), "cs-a", second.fencingToken()))
                .contains(ErrorCode.LEASE_OCCUPIED);
        assertThat(leases.heartbeat(second.leaseId(), "cs-b", second.fencingToken())).isEmpty();
    }

    @Test
    void expiryRevokesAndHeartbeatExtends() {
        Lease l = leases.acquire("qwen-01", "cs-a", 1).lease();
        clock.advance(Duration.ofSeconds(45));
        assertThat(leases.heartbeat(l.leaseId(), "cs-a", null)).isEmpty();
        clock.advance(Duration.ofSeconds(45));
        assertThat(leases.current("qwen-01")).as("renewed for another 60s").isPresent();
        clock.advance(Duration.ofSeconds(16));
        assertThat(leases.current("qwen-01")).isEmpty();
        assertThat(leases.sweepExpired()).extracting(Lease::leaseId).containsExactly(l.leaseId());
        assertThat(events.of(LeaseEvents.Revocation.class)).extracting(LeaseEvents.Revocation::leaseId)
                .contains(l.leaseId());
        assertThat(leases.acquire("qwen-01", "cs-b", 1).ok()).isTrue();
    }

    @Test
    void newGenerationEvictsLeaseFromOldGeneration() {
        Lease old = leases.acquire("qwen-01", "cs-a", 1).lease();
        LeaseManager.AcquireResult r = leases.acquire("qwen-01", "cs-b", 2);
        assertThat(r.ok()).isTrue();
        assertThat(events.of(LeaseEvents.Revocation.class))
                .anySatisfy(e -> {
                    assertThat(e.leaseId()).isEqualTo(old.leaseId());
                    assertThat(e.reason()).isEqualTo(ErrorCode.STALE_GENERATION);
                });
        assertThat(leases.isCurrent("qwen-01", old.leaseId(), old.fencingToken(), 1)).isFalse();
    }

    @Test
    void revocationsPublishFenceChanges() {
        Lease l = leases.acquire("codex-01", "cs-a", 1).lease();
        leases.revokeInstance("codex-01", ErrorCode.COMPATIBILITY_BLOCKED);
        assertThat(events.of(LeaseEvents.FenceChanged.class)).hasSize(2);
        assertThat(leases.releaseAllHeldBy("cs-a", ErrorCode.UNAUTHENTICATED)).isEmpty();
        assertThat(leases.findById(l.leaseId())).isEmpty();
    }
}
