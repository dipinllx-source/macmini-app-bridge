package io.github.dipinllx.minimacappbridge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.minimacappbridge.proxy.BoundedFrameQueue.Budget;
import io.github.dipinllx.minimacappbridge.proxy.BoundedFrameQueue.Lane;
import io.github.dipinllx.minimacappbridge.proxy.BoundedFrameQueue.Offer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;

/** 慢客户端隔离：同一生产者向两个出站缓冲投递，慢的被断开，快的全部收到，生产者从不阻塞（设计 §9.3）。 */
class SessionOutboxTest {

    /** 从不请求数据的下游（模拟不读 socket 的浏览器）。 */
    static final class Stalled extends BaseSubscriber<String> {
        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // no demand
        }
    }

    static final class Draining extends BaseSubscriber<String> {
        final AtomicInteger received = new AtomicInteger();

        @Override
        protected void hookOnNext(String value) {
            received.incrementAndGet();
        }
    }

    @Test
    void slowConsumerIsCutOffWhileFastConsumerKeepsUpAndProducerNeverBlocks() {
        AtomicInteger slowOverflows = new AtomicInteger();
        AtomicInteger fastOverflows = new AtomicInteger();
        SessionOutbox slow = new SessionOutbox(Budget.outbound(), slowOverflows::incrementAndGet);
        SessionOutbox fast = new SessionOutbox(Budget.outbound(), fastOverflows::incrementAndGet);
        slow.flux().subscribe(new Stalled());
        Draining fastSink = new Draining();
        fast.flux().subscribe(fastSink);

        String frame = "x".repeat(256 * 1024);
        int frames = 64; // 16 MiB total, 4x the 4 MiB slow-client budget
        List<Offer> slowResults = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            slowResults.add(slow.offer(frame, Lane.DATA, null));
            assertThat(fast.offer(frame, Lane.DATA, null).accepted()).isTrue();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).as("producer is never blocked by the stalled consumer").isLessThan(2_000);
        assertThat(fastSink.received.get()).isEqualTo(frames);
        assertThat(fastOverflows.get()).isZero();
        assertThat(slow.overflowed()).isTrue();
        assertThat(slowOverflows.get()).as("overflow callback fires once").isEqualTo(1);
        assertThat(slowResults).contains(Offer.OVERFLOW);
        assertThat(slow.queuedBytes()).isLessThanOrEqualTo(Budget.outbound().maxBytes());
    }

    @Test
    void emitsOnlyOnDemand() {
        SessionOutbox box = new SessionOutbox(Budget.outbound(), () -> { });
        List<String> got = new ArrayList<>();
        BaseSubscriber<String> one = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            protected void hookOnNext(String v) {
                got.add(v);
            }
        };
        box.flux().subscribe(one);
        box.offer("a", Lane.DATA, null);
        box.offer("b", Lane.DATA, null);
        assertThat(got).containsExactly("a");
        assertThat(box.queuedBytes()).isEqualTo(1);
        one.request(5);
        assertThat(got).containsExactly("a", "b");
    }

    @Test
    void concurrentProducersLoseNothing() throws Exception {
        SessionOutbox box = new SessionOutbox(Budget.outbound(), () -> { });
        Draining sink = new Draining();
        box.flux().subscribe(sink);
        int producers = 4;
        int each = 500;
        CountDownLatch done = new CountDownLatch(producers);
        for (int p = 0; p < producers; p++) {
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < each; i++) {
                    box.offer("m", Lane.DATA, null);
                }
                done.countDown();
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.received.get()).isEqualTo(producers * each);
    }
}
