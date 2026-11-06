package io.github.dipinllx.minimacappbridge.proxy;

import io.github.dipinllx.minimacappbridge.proxy.BoundedFrameQueue.Lane;
import io.github.dipinllx.minimacappbridge.proxy.BoundedFrameQueue.Offer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * 每个浏览器连接一个出站缓冲。生产者（适配器读线程）只做非阻塞 {@link #offer}，永不等待慢客户端；
 * 缓冲超过预算即触发 {@code onOverflow}（断开该连接），其他连接不受影响（设计 §9.3）。
 *
 * <p>消费端按需拉取：{@link #flux()} 只有在下游请求时才出队，因此"已交给网络栈但未写出"的数据
 * 最多一帧，其余都计入本缓冲的字节预算。
 */
public final class SessionOutbox {

    private final BoundedFrameQueue<String> queue;
    private final Runnable onOverflow;
    private FluxSink<String> sink;
    private long requested;
    private final AtomicInteger wip = new AtomicInteger();
    private boolean overflowed;

    public SessionOutbox(BoundedFrameQueue.Budget budget, Runnable onOverflow) {
        this.queue = new BoundedFrameQueue<>(budget);
        this.onOverflow = onOverflow;
    }

    public Offer offer(String frame, Lane lane, String coalesceKey) {
        Offer r = queue.offer(frame, frame.getBytes(StandardCharsets.UTF_8).length, lane, coalesceKey);
        if (r == Offer.OVERFLOW || r == Offer.OVERSIZE) {
            boolean first;
            synchronized (this) {
                first = !overflowed;
                overflowed = true;
            }
            if (first) {
                onOverflow.run();
            }
        } else if (r.accepted()) {
            drain();
        }
        return r;
    }

    public Flux<String> flux() {
        return Flux.create(s -> {
            synchronized (this) {
                sink = s;
            }
            s.onRequest(n -> {
                synchronized (this) {
                    requested = requested + n < 0 ? Long.MAX_VALUE : requested + n;
                }
                drain();
            });
            s.onDispose(queue::close);
        });
    }

    public void complete() {
        queue.close();
        FluxSink<String> s;
        synchronized (this) {
            s = sink;
        }
        if (s != null) {
            s.complete();
        }
    }

    public long queuedBytes() {
        return queue.queuedBytes();
    }

    public boolean overflowed() {
        synchronized (this) {
            return overflowed;
        }
    }

    /** WIP 计数的串行出队：并发 offer / request 不会漏发，也不会两个线程同时 next。 */
    private void drain() {
        if (wip.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        do {
            while (true) {
                FluxSink<String> s;
                String next;
                synchronized (this) {
                    if (sink == null || requested == 0) {
                        break;
                    }
                    next = queue.poll();
                    if (next == null) {
                        break;
                    }
                    if (requested != Long.MAX_VALUE) {
                        requested--;
                    }
                    s = sink;
                }
                s.next(next);
            }
            missed = wip.addAndGet(-missed);
        } while (missed != 0);
    }
}
