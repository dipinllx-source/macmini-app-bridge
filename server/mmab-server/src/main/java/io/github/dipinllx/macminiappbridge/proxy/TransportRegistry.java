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

    public synchronized boolean attach(BridgeConnection c) {
        BridgeConnection existing = byLease.get(c.leaseId());
        if (existing != null && (existing.suspended() || existing.isClosed())) {
            // 页面重新加载 / 另一个页面接手：挂起中的旧连接不会再被恢复，立即正式关闭；已关闭的残留直接移除
            existing.discardSuspended();
            detach(existing);
        }
        if (byLease.putIfAbsent(c.leaseId(), c) != null) {
            return false;
        }
        byConnection.put(c.connectionId(), c);
        return true;
    }

    /** 恢复：按连接 ID 找到挂起中的连接（由调用方校验会话与租约）。 */
    public java.util.Optional<BridgeConnection> suspended(String connectionId) {
        BridgeConnection c = connectionId == null ? null : byConnection.get(connectionId);
        return c != null && c.suspended() ? java.util.Optional.of(c) : java.util.Optional.empty();
    }

    /** 打开中的连接由平台续租约（不依赖后台标签页被限流的心跳）。 */
    public void keepAlive() {
        for (BridgeConnection c : List.copyOf(byConnection.values())) {
            c.keepLeaseAlive(resumeGrace);
        }
    }

    private volatile java.time.Duration resumeGrace = BridgeConnection.RESUME_GRACE;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configure(io.github.dipinllx.macminiappbridge.config.MmabProperties properties) {
        resumeGrace = properties.transport().resumeGrace();
    }

    /**
     * 让位：实例的租约只被“挂起中”的连接持有（页面已断开、等待恢复）时，正式关闭它并释放租约，
     * 让新的页面 / 设备可以打开该实例。
     *
     * @return 是否释放了租约
     */
    public synchronized boolean yieldSuspended(String instanceId, io.github.dipinllx.macminiappbridge.leases.LeaseManager leases) {
        var current = leases.current(instanceId);
        if (current.isEmpty()) {
            return false;
        }
        BridgeConnection holder = byLease.get(current.get().leaseId());
        if (holder == null || !holder.suspended()) {
            return false;
        }
        holder.discardSuspended();
        detach(holder);
        leases.revokeInstance(instanceId, io.github.dipinllx.macminiappbridge.protocol.ErrorCode.LEASE_REQUIRED);
        return true;
    }

    /** 宽限期已过的挂起连接：正式关闭并移除（MaintenanceSweeper 周期调用）。 */
    public int sweepSuspended() {
        int n = 0;
        for (BridgeConnection c : List.copyOf(byConnection.values())) {
            if (c.expireIfSuspendedLongerThan(resumeGrace) || (c.isClosed() && !c.suspended())) {
                detach(c);
                n++;
            }
        }
        return n;
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
                if (c.isClosed()) {
                    detach(c); // 挂起中的连接没有传输回调来移除自己
                }
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
