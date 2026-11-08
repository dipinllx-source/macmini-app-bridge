package io.github.dipinllx.macminiappbridge.proxy;

import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.MessageKind;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 同步调用（{@code POST /__mmab/bridge/call}）的待决表（M3）。每个调用用独立的 {@code sync-*} connectionId，
 * 适配器的 RESPONSE 按 connectionId + correlationId 精确配对；超时后迟到的响应被丢弃（执行状态未知）。
 * 每实例并发上限 {@link #MAX_PER_INSTANCE}，超过即 QUEUE_LIMIT。
 */
@Component
public class SyncCallRegistry {

    /** 连接 ID 前缀：与浏览器 WS 连接（{@code c-*}）区分。 */
    public static final String PREFIX = "sync-";
    public static final int MAX_PER_INSTANCE = 16;

    private record Call(String instanceId, String correlationId, CompletableFuture<Envelope> future) {
    }

    private final Map<String, Call> calls = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perInstance = new ConcurrentHashMap<>();

    /** @return null 表示超出并发预算 */
    public CompletableFuture<Envelope> register(String instanceId, String connectionId, String correlationId) {
        AtomicInteger n = perInstance.computeIfAbsent(instanceId, k -> new AtomicInteger());
        if (n.incrementAndGet() > MAX_PER_INSTANCE) {
            n.decrementAndGet();
            return null;
        }
        CompletableFuture<Envelope> f = new CompletableFuture<>();
        calls.put(connectionId, new Call(instanceId, correlationId, f));
        f.whenComplete((v, t) -> {
            if (calls.remove(connectionId) != null) {
                n.decrementAndGet();
            }
        });
        return f;
    }

    public static boolean isSync(String connectionId) {
        return connectionId != null && connectionId.startsWith(PREFIX);
    }

    /** 适配器帧 → 待决调用；只接受同实例、同 correlationId 的 RESPONSE，其余丢弃。 */
    public boolean complete(String instanceId, Envelope e) {
        Call c = calls.get(e.connectionId());
        if (c == null || !c.instanceId().equals(instanceId) || e.messageKind() != MessageKind.RESPONSE
                || !c.correlationId().equals(e.correlationId())) {
            return false;
        }
        return c.future().complete(e);
    }

    public int pending() {
        return calls.size();
    }
}
