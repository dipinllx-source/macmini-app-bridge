package io.github.dipinllx.minimacappbridge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.minimacappbridge.proxy.BoundedFrameQueue.Budget;
import io.github.dipinllx.minimacappbridge.proxy.BoundedFrameQueue.Lane;
import io.github.dipinllx.minimacappbridge.proxy.BoundedFrameQueue.Offer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** 有界队列：帧 / 字节上限、单帧上限、控制保留额度、优先级、合并、高低水位（设计 §9.3）。 */
class BoundedFrameQueueTest {

    private static Budget small() {
        return new Budget(4, 1000, 400, 2, 100, 0.75, 0.25);
    }

    @Test
    void designStartingBudgets() {
        Budget in = Budget.inbound();
        assertThat(in.maxFrames()).isEqualTo(2048);
        assertThat(in.maxBytes()).isEqualTo(8L * 1024 * 1024);
        assertThat(Budget.outbound().maxBytes()).isEqualTo(4L * 1024 * 1024);
    }

    @Test
    void frameCountAndByteLimitsOverflowInsteadOfDropping() {
        BoundedFrameQueue<String> q = new BoundedFrameQueue<>(small());
        for (int i = 0; i < 4; i++) {
            assertThat(q.offer("f" + i, 10, Lane.DATA, null)).isEqualTo(Offer.ACCEPTED);
        }
        assertThat(q.offer("f4", 10, Lane.DATA, null)).isEqualTo(Offer.OVERFLOW);
        assertThat(q.snapshot()).containsExactly("f0", "f1", "f2", "f3");

        BoundedFrameQueue<String> bytes = new BoundedFrameQueue<>(small());
        assertThat(bytes.offer("a", 400, Lane.DATA, null)).isEqualTo(Offer.ACCEPTED);
        assertThat(bytes.offer("b", 400, Lane.DATA, null)).isEqualTo(Offer.ACCEPTED);
        assertThat(bytes.offer("c", 400, Lane.DATA, null)).isEqualTo(Offer.OVERFLOW);
        assertThat(bytes.offer("big", 401, Lane.DATA, null)).isEqualTo(Offer.OVERSIZE);
        assertThat(bytes.overflowCount()).isEqualTo(2);
    }

    @Test
    void controlLaneIsReservedAndServedFirst() {
        BoundedFrameQueue<String> q = new BoundedFrameQueue<>(small());
        for (int i = 0; i < 4; i++) {
            q.offer("d" + i, 10, Lane.DATA, null);
        }
        assertThat(q.offer("d-x", 10, Lane.DATA, null)).isEqualTo(Offer.OVERFLOW);
        assertThat(q.offer("heartbeat", 10, Lane.CONTROL, null)).as("data full, control still accepted")
                .isEqualTo(Offer.ACCEPTED);
        assertThat(q.poll()).isEqualTo("heartbeat");
        assertThat(q.poll()).isEqualTo("d0");
        q.offer("c1", 50, Lane.CONTROL, null);
        q.offer("c2", 50, Lane.CONTROL, null);
        assertThat(q.offer("c3", 1, Lane.CONTROL, null)).as("control reserve is bounded too").isEqualTo(Offer.OVERFLOW);
    }

    @Test
    void onlyExplicitlyCoalescibleFramesAreMerged() {
        BoundedFrameQueue<String> q = new BoundedFrameQueue<>(small());
        q.offer("tick-1", 10, Lane.DATA, "mock.tick");
        q.offer("chunk-1", 10, Lane.DATA, null);
        assertThat(q.offer("tick-2", 10, Lane.DATA, "mock.tick")).isEqualTo(Offer.COALESCED);
        q.offer("chunk-2", 10, Lane.DATA, null);
        assertThat(q.snapshot()).containsExactly("tick-2", "chunk-1", "chunk-2");
        assertThat(q.coalescedCount()).isEqualTo(1);
    }

    @Test
    void highAndLowWatermarksPauseAndResume() {
        BoundedFrameQueue<String> q = new BoundedFrameQueue<>(small());
        q.offer("a", 300, Lane.DATA, null);
        assertThat(q.isPaused()).isFalse();
        q.offer("b", 300, Lane.DATA, null);
        q.offer("c", 200, Lane.DATA, null);
        assertThat(q.isPaused()).as("800 >= 750 high water").isTrue();
        Mono<Void> resumed = q.whenWritable();
        q.poll();
        assertThat(q.isPaused()).as("500 bytes still above the 250 low water").isTrue();
        q.poll();
        assertThat(q.isPaused()).as("200 bytes / 1 frame: below low water").isFalse();
        assertThat(resumed.block(Duration.ofSeconds(1))).isNull();
        assertThat(q.whenWritable().block(Duration.ofSeconds(1))).isNull();
    }

    @Test
    void closeRejectsAndWakesWaiters() throws InterruptedException {
        BoundedFrameQueue<String> q = new BoundedFrameQueue<>(small());
        q.offer("a", 800, Lane.DATA, null);
        Mono<Void> waiting = q.whenWritable();
        q.close();
        assertThat(waiting.block(Duration.ofSeconds(1))).isNull();
        assertThat(q.offer("b", 1, Lane.DATA, null)).isEqualTo(Offer.CLOSED);
        assertThat(q.take(Duration.ofMillis(10))).isNull();
    }
}
