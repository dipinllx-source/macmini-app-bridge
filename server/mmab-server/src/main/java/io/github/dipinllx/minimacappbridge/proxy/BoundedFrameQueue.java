package io.github.dipinllx.minimacappbridge.proxy;

import io.github.dipinllx.minimacappbridge.protocol.AdapterProtocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 有界帧队列（设计 §9.3）：帧数 / 字节双维上限、单帧上限、控制 / 心跳保留额度（不会被大消息饿死，
 * 且优先出队）、高低水位暂停 / 恢复、仅对显式可合并的状态通知做合并。
 *
 * <p>队列满时<strong>不静默丢弃</strong>业务帧：{@link #offer} 返回 {@link Offer#OVERFLOW}，
 * 由调用方按策略处理（入站：向发送方回 QUEUE_LIMIT；出站：断开慢客户端）。
 *
 * @param <T> 帧类型
 */
public final class BoundedFrameQueue<T> {

    public enum Lane { CONTROL, DATA }

    public enum Offer {
        /** 已入队。 */
        ACCEPTED,
        /** 与同 key 的待发状态通知合并（替换原位置的旧值）。 */
        COALESCED,
        /** 单帧超过上限。 */
        OVERSIZE,
        /** 队列已满。 */
        OVERFLOW,
        /** 队列已关闭。 */
        CLOSED;

        public boolean accepted() {
            return this == ACCEPTED || this == COALESCED;
        }
    }

    /**
     * @param maxFrames     数据通道帧数上限
     * @param maxBytes      数据通道字节上限
     * @param maxFrameBytes 单帧上限
     * @param controlFrames 控制通道保留帧数
     * @param controlBytes  控制通道保留字节
     * @param highWater     高水位比例：超过即进入暂停（停止读取上游）
     * @param lowWater      低水位比例：降到以下才恢复
     */
    public record Budget(int maxFrames, long maxBytes, int maxFrameBytes, int controlFrames, long controlBytes,
            double highWater, double lowWater) {
        public Budget {
            if (maxFrames <= 0 || maxBytes <= 0 || maxFrameBytes <= 0 || controlFrames <= 0 || controlBytes <= 0) {
                throw new IllegalArgumentException("budget values must be positive");
            }
            if (!(lowWater > 0 && lowWater < highWater && highWater <= 1)) {
                throw new IllegalArgumentException("require 0 < lowWater < highWater <= 1");
            }
        }

        /** 浏览器 → 适配器：2048 帧 / 8 MiB / 单帧 1 MiB（设计 §9.3 起始值）。 */
        public static Budget inbound() {
            return new Budget(AdapterProtocol.MAX_QUEUED_FRAMES, AdapterProtocol.MAX_QUEUED_BYTES,
                    AdapterProtocol.MAX_WIRE_LINE_BYTES, AdapterProtocol.CONTROL_RESERVED_FRAMES,
                    AdapterProtocol.CONTROL_RESERVED_BYTES, 0.75, 0.25);
        }

        /** 适配器 → 浏览器：出站缓冲 4 MiB 即判慢客户端（设计 §9.3 起始值）。 */
        public static Budget outbound() {
            return new Budget(AdapterProtocol.MAX_QUEUED_FRAMES, AdapterProtocol.SLOW_CLIENT_BUFFERED_BYTES,
                    AdapterProtocol.MAX_WIRE_LINE_BYTES, AdapterProtocol.CONTROL_RESERVED_FRAMES,
                    AdapterProtocol.CONTROL_RESERVED_BYTES, 0.75, 0.25);
        }
    }

    private record Item<T>(T value, long bytes, String coalesceKey) {
    }

    private final Budget budget;
    private final LinkedList<Item<T>> control = new LinkedList<>();
    private final LinkedList<Item<T>> data = new LinkedList<>();
    private long controlBytes;
    private long dataBytes;
    private boolean closed;
    private boolean paused;
    private Sinks.Empty<Void> resume = Sinks.empty();
    private long overflowCount;
    private long coalescedCount;

    public BoundedFrameQueue(Budget budget) {
        this.budget = budget;
    }

    public Offer offer(T value, long bytes, Lane lane, String coalesceKey) {
        synchronized (this) {
            if (closed) {
                return Offer.CLOSED;
            }
            if (bytes > budget.maxFrameBytes()) {
                overflowCount++;
                return Offer.OVERSIZE;
            }
            if (lane == Lane.CONTROL) {
                if (control.size() >= budget.controlFrames() || controlBytes + bytes > budget.controlBytes()) {
                    overflowCount++;
                    return Offer.OVERFLOW;
                }
                control.addLast(new Item<>(value, bytes, null));
                controlBytes += bytes;
                notifyAll();
                return Offer.ACCEPTED;
            }
            if (coalesceKey != null) {
                ListIterator<Item<T>> it = data.listIterator();
                while (it.hasNext()) {
                    Item<T> existing = it.next();
                    if (coalesceKey.equals(existing.coalesceKey())) {
                        if (dataBytes - existing.bytes() + bytes > budget.maxBytes()) {
                            overflowCount++;
                            return Offer.OVERFLOW;
                        }
                        it.set(new Item<>(value, bytes, coalesceKey));
                        dataBytes += bytes - existing.bytes();
                        coalescedCount++;
                        updatePauseLocked();
                        return Offer.COALESCED;
                    }
                }
            }
            if (data.size() >= budget.maxFrames() || dataBytes + bytes > budget.maxBytes()) {
                overflowCount++;
                return Offer.OVERFLOW;
            }
            data.addLast(new Item<>(value, bytes, coalesceKey));
            dataBytes += bytes;
            updatePauseLocked();
            notifyAll();
            return Offer.ACCEPTED;
        }
    }

    /** 非阻塞取帧：控制通道优先。 */
    public T poll() {
        Sinks.Empty<Void> toComplete = null;
        T value;
        synchronized (this) {
            Item<T> item = control.pollFirst();
            if (item != null) {
                controlBytes -= item.bytes();
                return item.value();
            }
            item = data.pollFirst();
            if (item == null) {
                return null;
            }
            dataBytes -= item.bytes();
            value = item.value();
            if (paused && belowLowWaterLocked()) {
                paused = false;
                toComplete = resume;
                resume = Sinks.empty();
            }
        }
        if (toComplete != null) {
            toComplete.tryEmitEmpty();
        }
        return value;
    }

    /** 阻塞取帧（单写线程使用）；超时返回 null，关闭后返回 null。 */
    public T take(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this) {
            while (!closed && control.isEmpty() && data.isEmpty()) {
                long waitNanos = deadline - System.nanoTime();
                if (waitNanos <= 0) {
                    return null;
                }
                wait(Math.max(1, waitNanos / 1_000_000));
            }
            if (closed) {
                return null;
            }
        }
        return poll();
    }

    /** 高水位时返回一个在降到低水位（或关闭）时完成的 Mono；否则立即完成。 */
    public Mono<Void> whenWritable() {
        synchronized (this) {
            if (!paused || closed) {
                return Mono.empty();
            }
            return resume.asMono();
        }
    }

    public void close() {
        Sinks.Empty<Void> toComplete;
        synchronized (this) {
            closed = true;
            control.clear();
            data.clear();
            controlBytes = 0;
            dataBytes = 0;
            paused = false;
            toComplete = resume;
            notifyAll();
        }
        toComplete.tryEmitEmpty();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int size() {
        return control.size() + data.size();
    }

    public synchronized long queuedBytes() {
        return controlBytes + dataBytes;
    }

    public synchronized long overflowCount() {
        return overflowCount;
    }

    public synchronized long coalescedCount() {
        return coalescedCount;
    }

    /** 测试 / 诊断：当前数据通道内容快照。 */
    public synchronized List<T> snapshot() {
        List<T> out = new ArrayList<>();
        control.forEach(i -> out.add(i.value()));
        data.forEach(i -> out.add(i.value()));
        return out;
    }

    private void updatePauseLocked() {
        if (!paused && (dataBytes >= budget.highWater() * budget.maxBytes()
                || data.size() >= budget.highWater() * budget.maxFrames())) {
            paused = true;
        }
    }

    private boolean belowLowWaterLocked() {
        return dataBytes <= budget.lowWater() * budget.maxBytes() && data.size() <= budget.lowWater() * budget.maxFrames();
    }
}
