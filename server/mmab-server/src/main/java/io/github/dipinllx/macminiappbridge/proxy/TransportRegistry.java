package io.github.dipinllx.macminiappbridge.proxy;

import io.github.dipinllx.macminiappbridge.leases.LeaseEvents;
import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 活动数据面连接表。每个租约最多一条连接（第二个标签页得到 WS 1013，第一个不受影响，设计 §9.1）；
 * 撤销事件到达时关闭匹配的连接。
 */
@Component
public class TransportRegistry {

    private final Map<String, BridgeConnection> byConnection = new ConcurrentHashMap<>();
    private final Map<String, BridgeConnection> byLease = new ConcurrentHashMap<>();
    private final AtomicLong droppedFrames = new AtomicLong();

    public boolean attach(BridgeConnection c) {
        if (byLease.putIfAbsent(c.leaseId(), c) != null) {
            return false;
        }
        byConnection.put(c.connectionId(), c);
        return true;
    }

    public void detach(BridgeConnection c) {
        byLease.remove(c.leaseId(), c);
        byConnection.remove(c.connectionId(), c);
    }

    /** 适配器帧 → 对应浏览器连接；连接不存在或实例不符则丢弃并计数。 */
    public void route(String instanceId, Envelope e) {
        BridgeConnection c = byConnection.get(e.connectionId());
        if (c == null || !c.instanceId().equals(instanceId) || !c.leaseId().equals(e.leaseId())) {
            droppedFrames.incrementAndGet();
            return;
        }
        c.deliver(e);
    }

    @EventListener
    public void onRevocation(LeaseEvents.Revocation r) {
        if (r.instanceId() == null && r.leaseId() == null && r.instanceSessionId() == null
                && r.consoleSessionId() == null) {
            return;
        }
        for (BridgeConnection c : List.copyOf(byConnection.values())) {
            if ((r.instanceId() == null || r.instanceId().equals(c.instanceId()))
                    && (r.leaseId() == null || r.leaseId().equals(c.leaseId()))
                    && (r.instanceSessionId() == null || r.instanceSessionId().equals(c.session().id()))
                    && (r.consoleSessionId() == null || r.consoleSessionId().equals(c.session().parentSessionId()))) {
                c.closePolicy(r.reason());
            }
        }
    }

    /** 端口闲置回收（设计 §9.4：建议 5 分钟），由 MaintenanceSweeper 周期调用。 */
    public void reapIdlePorts() {
        for (BridgeConnection c : List.copyOf(byConnection.values())) {
            c.reapIdlePorts();
        }
    }

    public int activeConnections() {
        return byConnection.size();
    }

    public boolean isOpen(String connectionId) {
        return byConnection.containsKey(connectionId);
    }

    public List<BridgeConnection> connections() {
        return List.copyOf(byConnection.values());
    }

    public long droppedFrames() {
        return droppedFrames.get();
    }
}
