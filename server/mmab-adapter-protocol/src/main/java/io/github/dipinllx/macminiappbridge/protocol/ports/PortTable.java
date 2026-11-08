package io.github.dipinllx.macminiappbridge.protocol.ports;

import io.github.dipinllx.macminiappbridge.protocol.AdapterProtocol;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每连接的 MessagePort 生命周期表（设计 §9.4）：open / transfer / data / close / 对端丢失 / 闲置回收。
 *
 * <p>不变量：端口 ID 在连接内唯一（关闭后也不复用）；只有当前 owner 可以发数据、转移或关闭；
 * 转移后旧 owner 的任何操作被拒；关闭后的帧被拒（{@link ErrorCode#PORT_STATE}），不会被静默接到新端口。
 * M1 只有契约实现与测试，尚未接入真实应用的端口中继。
 */
public final class PortTable {

    public enum State { OPEN, CLOSED }

    /** 端口快照。 */
    public record PortInfo(String id, String owner, State state, int depth, Instant lastActivity) {
    }

    private static final int TOMBSTONE_LIMIT = 4096;

    private final int maxOpenPorts;
    private final int maxDepth;
    private final Duration idleTimeout;
    private final Clock clock;
    private final Map<String, PortInfo> ports = new LinkedHashMap<>();
    private int openCount;

    public PortTable(Clock clock) {
        this(AdapterProtocol.MAX_PORTS_PER_CONNECTION, AdapterProtocol.MAX_NESTING_DEPTH,
                Duration.ofSeconds(AdapterProtocol.PORT_IDLE_SECONDS), clock);
    }

    public PortTable(int maxOpenPorts, int maxDepth, Duration idleTimeout, Clock clock) {
        this.maxOpenPorts = maxOpenPorts;
        this.maxDepth = maxDepth;
        this.idleTimeout = idleTimeout;
        this.clock = clock;
    }

    /**
     * 打开端口。{@code parentPortId} 非空表示该端口经由父端口的 transfer list 传入（嵌套端口）。
     */
    public synchronized PortInfo open(String portId, String owner, String parentPortId) {
        if (ports.containsKey(portId)) {
            throw new ProtocolException(ErrorCode.PORT_STATE, "port id already used");
        }
        int depth = 1;
        if (parentPortId != null) {
            PortInfo parent = requireOpen(parentPortId, owner);
            depth = parent.depth() + 1;
        }
        if (depth > maxDepth) {
            throw new ProtocolException(ErrorCode.QUEUE_LIMIT, "port nesting depth exceeds " + maxDepth);
        }
        if (openCount >= maxOpenPorts) {
            throw new ProtocolException(ErrorCode.QUEUE_LIMIT, "open port limit " + maxOpenPorts);
        }
        PortInfo info = new PortInfo(portId, owner, State.OPEN, depth, clock.instant());
        ports.put(portId, info);
        openCount++;
        return info;
    }

    /** 数据帧：端口必须 OPEN 且发送方是当前 owner。 */
    public synchronized void data(String portId, String sender) {
        PortInfo p = requireOpen(portId, sender);
        ports.put(portId, touch(p));
    }

    /** 所有权转移：只能由当前 owner 发起；重复转移（旧 owner 再转）被拒。 */
    public synchronized PortInfo transfer(String portId, String fromOwner, String toOwner) {
        PortInfo p = requireOpen(portId, fromOwner);
        if (fromOwner.equals(toOwner)) {
            throw new ProtocolException(ErrorCode.PORT_STATE, "transfer to self");
        }
        PortInfo moved = new PortInfo(p.id(), toOwner, State.OPEN, p.depth(), clock.instant());
        ports.put(portId, moved);
        return moved;
    }

    /**
     * 关闭（半关闭语义：一侧 close 即整体 CLOSED，另一侧随后的帧被拒）。
     *
     * @return true 表示本次关闭；false 表示早已关闭（幂等，不报错）
     */
    public synchronized boolean close(String portId, String sender) {
        PortInfo p = ports.get(portId);
        if (p == null) {
            throw new ProtocolException(ErrorCode.PORT_STATE, "unknown port");
        }
        if (p.state() == State.CLOSED) {
            return false;
        }
        if (!p.owner().equals(sender)) {
            throw new ProtocolException(ErrorCode.PORT_STATE, "not the port owner");
        }
        markClosed(p);
        return true;
    }

    /** 对端丢失（例如 renderer 重建 / 连接断开）：关闭该 owner 的全部端口，返回被关闭的 ID。 */
    public synchronized List<String> peerLost(String owner) {
        List<String> closed = new ArrayList<>();
        for (PortInfo p : List.copyOf(ports.values())) {
            if (p.state() == State.OPEN && p.owner().equals(owner)) {
                markClosed(p);
                closed.add(p.id());
            }
        }
        return closed;
    }

    /** 闲置回收：超过 idleTimeout 无活动的 OPEN 端口被关闭。 */
    public synchronized List<String> reapIdle() {
        Instant cutoff = clock.instant().minus(idleTimeout);
        List<String> closed = new ArrayList<>();
        for (PortInfo p : List.copyOf(ports.values())) {
            if (p.state() == State.OPEN && p.lastActivity().isBefore(cutoff)) {
                markClosed(p);
                closed.add(p.id());
            }
        }
        return closed;
    }

    /** 连接关闭：所有端口关闭。 */
    public synchronized List<String> closeAll() {
        List<String> closed = new ArrayList<>();
        for (PortInfo p : List.copyOf(ports.values())) {
            if (p.state() == State.OPEN) {
                markClosed(p);
                closed.add(p.id());
            }
        }
        return closed;
    }

    public synchronized int openCount() {
        return openCount;
    }

    public synchronized PortInfo get(String portId) {
        return ports.get(portId);
    }

    private PortInfo requireOpen(String portId, String actor) {
        PortInfo p = ports.get(portId);
        if (p == null) {
            throw new ProtocolException(ErrorCode.PORT_STATE, "unknown port");
        }
        if (p.state() != State.OPEN) {
            throw new ProtocolException(ErrorCode.PORT_STATE, "port closed");
        }
        if (!p.owner().equals(actor)) {
            throw new ProtocolException(ErrorCode.PORT_STATE, "not the port owner");
        }
        return p;
    }

    private PortInfo touch(PortInfo p) {
        return new PortInfo(p.id(), p.owner(), p.state(), p.depth(), clock.instant());
    }

    private void markClosed(PortInfo p) {
        ports.put(p.id(), new PortInfo(p.id(), p.owner(), State.CLOSED, p.depth(), clock.instant()));
        openCount--;
        trimTombstones();
    }

    private void trimTombstones() {
        int tombstones = ports.size() - openCount;
        if (tombstones <= TOMBSTONE_LIMIT) {
            return;
        }
        Iterator<PortInfo> it = ports.values().iterator();
        while (it.hasNext() && tombstones > TOMBSTONE_LIMIT) {
            if (it.next().state() == State.CLOSED) {
                it.remove();
                tombstones--;
            }
        }
    }
}
