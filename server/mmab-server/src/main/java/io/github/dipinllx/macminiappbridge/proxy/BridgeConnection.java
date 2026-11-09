package io.github.dipinllx.macminiappbridge.proxy;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.identity.InstanceSession;
import io.github.dipinllx.macminiappbridge.instances.AdapterDescriptor;
import io.github.dipinllx.macminiappbridge.instances.InstanceAccess;
import io.github.dipinllx.macminiappbridge.instances.InstanceRuntime;
import io.github.dipinllx.macminiappbridge.instances.ProcessAdapterClient;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.AdapterProtocol;
import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.MessageKind;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolException;
import io.github.dipinllx.macminiappbridge.protocol.StrictJson;
import io.github.dipinllx.macminiappbridge.protocol.codec.StructuredCodec;
import io.github.dipinllx.macminiappbridge.protocol.ports.PortTable;
import io.github.dipinllx.macminiappbridge.proxy.BoundedFrameQueue.Lane;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.adapter.MmabSocketAbort;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 一条浏览器数据面连接（实例端口上的 WS）。浏览器帧格式见 {@code sdk/protocol/README.md}：
 *
 * <pre>
 * → {"kind":"REQUEST","op":"mock.echo","cid":"1","payload":"&lt;mmab-sv1&gt;"}
 * → {"kind":"HEARTBEAT"}
 * ← {"kind":"RESPONSE","cid":"1","payload":"..."} | {"kind":"RESPONSE","cid":"1","error":{...}}
 * ← {"kind":"EVENT","op":"mock.tick","payload":"..."}
 * ← {"kind":"HEARTBEAT"} | {"kind":"ERROR","error":{...}}
 * ↔ {"kind":"PORT_DATA","port":"w1","payload":"..."} | {"kind":"PORT_CLOSE","port":"w1"}   （M3 端口中继）
 * </pre>
 *
 * <p>浏览器不提供 instance / generation / lease / fence：这些由服务端按会话填入 envelope，
 * 浏览器无法伪造 fence。
 *
 * <p>M3 MessagePort 中继（设计 §9.4）：payload 中的 {@code {"$t":"port"}} 引用在本连接的 {@link PortTable} 中登记。
 * 浏览器创建的端口 ID 必须以 {@code w} 开头、适配器创建的以 {@code n} 开头（任何一方都不能冒用对方的 ID 空间）；
 * ID 在连接内不复用，关闭后的帧被拒，嵌套深度 / 端口数 / 闲置时间受预算约束，连接关闭时全部端口关闭，
 * 并以 {@code CONTROL mmab.close} 通知适配器释放原生侧端口。
 */
public final class BridgeConnection {

    /** WS 关闭码：策略拒绝（失效 / 撤销 / 慢客户端）。 */
    public static final int CLOSE_POLICY = 1008;
    /** WS 关闭码：租约已被其他标签页占用（设计 §9.1：WS 1013）。 */
    public static final int CLOSE_OCCUPIED = 1013;

    private static final Pattern CID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> FRAME_KEYS = Set.of("kind", "op", "cid", "payload", "port");
    private static final Pattern PORT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** 平台控制操作：浏览器连接关闭（平台 → 适配器）/ 要求关闭某浏览器连接（适配器 → 平台）。 */
    public static final String CLOSE_OPERATION = "mmab.close";

    private record Pending(String op, Instant deadline) {
    }

    private final String connectionId;
    private final InstanceSession session;
    private final String leaseId;
    private final long fencingToken;
    private final long generation;
    private final InstanceRuntime runtime;
    private volatile WebSocketSession ws;
    /** reliable-personal-entry：传输断开后在宽限期内保留端口与缓冲，等待同一页面恢复。 */
    // 2026-11-10 实测：浏览器后台标签页会限流定时器、甚至直接断开 WS（1006）；远端电脑休眠时连接必然断开。
    // 默认保留 12 小时（mmab.transport.resume-grace），醒来后同一页面无损恢复；其他页面 / 设备打开时保留中的连接让位。
    public static final Duration RESUME_GRACE = Duration.ofHours(12);
    /**
     * 连接存活判定：最近一次收到浏览器帧在此时限内，平台就替它续租约（不依赖页面心跳准时）。
     * 后台标签页的心跳会被限流到约每分钟一次，曾导致 60 秒租约过期、连接被以 LEASE_REQUIRED 关闭。
     */
    public static final Duration LIVENESS = Duration.ofMinutes(5);
    private volatile java.time.Instant lastInbound;
    private volatile java.time.Instant suspendedAt;
    private final SessionOutbox outbox;
    private final InstanceAccess access;
    private final LeaseManager leases;
    private final AuditLog audit;
    private final MmabClock clock;
    private final Duration requestDeadline;
    private final StructuredCodec codec = new StructuredCodec();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final PortTable ports;
    private volatile ErrorCode closeReason;

    BridgeConnection(String connectionId, InstanceSession session, InstanceRuntime runtime, WebSocketSession ws,
            InstanceAccess access, LeaseManager leases, AuditLog audit, MmabClock clock, Duration requestDeadline) {
        this(connectionId, session, runtime, ws, access, leases, audit, clock, requestDeadline,
                BoundedFrameQueue.Budget.outbound());
    }

    BridgeConnection(String connectionId, InstanceSession session, InstanceRuntime runtime, WebSocketSession ws,
            InstanceAccess access, LeaseManager leases, AuditLog audit, MmabClock clock, Duration requestDeadline,
            BoundedFrameQueue.Budget outboundBudget) {
        this.connectionId = connectionId;
        this.session = session;
        // 连接固定在建立时的租约绑定上（reliable-personal-entry：会话可重新绑定租约，但已建立的连接不迁移）
        this.leaseId = session.leaseId();
        this.fencingToken = session.fencingToken();
        this.generation = session.generation();
        this.runtime = runtime;
        this.ws = ws;
        this.access = access;
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
        this.requestDeadline = requestDeadline;
        this.outbox = new SessionOutbox(outboundBudget, this::onSlowConsumer);
        this.ports = new PortTable(clock);
    }

    /** 本连接的端口表（只读诊断 / 测试）。 */
    public PortTable ports() {
        return ports;
    }

    public String connectionId() {
        return connectionId;
    }

    public InstanceSession session() {
        return session;
    }

    /** 本连接建立时绑定的租约（不随会话重新绑定而改变）。 */
    public String leaseId() {
        return leaseId;
    }

    public String instanceId() {
        return session.instanceId();
    }

    SessionOutbox outbox() {
        return outbox;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public ErrorCode closeReason() {
        return closeReason;
    }

    public int pendingRequests() {
        return pending.size();
    }

    // ------------------------------------------------------------------ inbound (browser → adapter)

    /** 入站消息在接收时立即转成文本（不持有 Netty 缓冲区）。{@code text == null} 表示二进制帧。 */
    record Inbound(String text) {
        static Inbound of(WebSocketMessage m) {
            return new Inbound(m.getType() == WebSocketMessage.Type.TEXT ? m.getPayloadAsText() : null);
        }
    }

    Mono<Void> onInbound(Inbound message) {
        if (closed.get()) {
            return Mono.empty();
        }
        lastInbound = clock.instant();
        if (message.text() == null) {
            sendConnectionError(ErrorCode.MALFORMED_FRAME);
            return Mono.empty();
        }
        String text = message.text();
        JsonNode frame;
        try {
            frame = StrictJson.WIRE.parse(text);
        } catch (ProtocolException e) {
            sendConnectionError(ErrorCode.MALFORMED_FRAME);
            return Mono.empty();
        }
        if (!frame.isObject() || !frame.propertyNames().stream().allMatch(FRAME_KEYS::contains)
                || frame.get("kind") == null || !frame.get("kind").isString()) {
            sendConnectionError(ErrorCode.MALFORMED_FRAME);
            return Mono.empty();
        }
        ErrorCode invalid = access.check(session, leaseId, fencingToken, generation);
        if (invalid != null) {
            closePolicy(invalid);
            return Mono.empty();
        }
        return switch (frame.get("kind").stringValue()) {
            case "HEARTBEAT" -> {
                leases.touch(session.instanceId(), leaseId, fencingToken);
                access.touchActive(session);
                ObjectNode ack = StrictJson.WIRE.object();
                ack.put("kind", "HEARTBEAT");
                outbox.offer(StrictJson.WIRE.write(ack), Lane.CONTROL, null);
                yield Mono.empty();
            }
            case "REQUEST" -> onRequest(frame);
            case "PORT_DATA" -> onPortData(frame);
            case "PORT_CLOSE" -> onPortClose(frame);
            default -> {
                sendConnectionError(ErrorCode.MALFORMED_FRAME);
                yield Mono.empty();
            }
        };
    }

    private Mono<Void> onRequest(JsonNode frame) {
        JsonNode cidNode = frame.get("cid");
        JsonNode opNode = frame.get("op");
        JsonNode payloadNode = frame.get("payload");
        if (cidNode == null || !cidNode.isString() || !CID.matcher(cidNode.stringValue()).matches()) {
            sendConnectionError(ErrorCode.MALFORMED_FRAME);
            return Mono.empty();
        }
        String cid = cidNode.stringValue();
        if (opNode == null || !opNode.isString() || payloadNode == null || !payloadNode.isString()) {
            respondError(cid, ErrorCode.MALFORMED_FRAME);
            return Mono.empty();
        }
        String op = opNode.stringValue();
        AdapterDescriptor d = runtime.descriptor();
        var gate = d.gateRequest(op);
        if (gate.isPresent()) {
            audit.record(session.id(), "bridge.request", instanceId(), gate.get().name(), "gateway");
            respondError(cid, gate.get());
            return Mono.empty();
        }
        String payload = payloadNode.stringValue();
        try {
            openPorts(codec.portIds(payload), WEB, null);
        } catch (ProtocolException e) {
            respondError(cid, e.code());
            return Mono.empty();
        }
        if (pending.size() >= AdapterProtocol.MAX_PENDING_REQUESTS) {
            respondError(cid, ErrorCode.QUEUE_LIMIT);
            return Mono.empty();
        }
        Instant deadline = clock.instant().plus(requestDeadline);
        if (pending.putIfAbsent(cid, new Pending(op, deadline)) != null) {
            respondError(cid, ErrorCode.MALFORMED_FRAME); // 重复 cid
            return Mono.empty();
        }
        ProcessAdapterClient client = runtime.client();
        if (client == null || !runtime.ready()) {
            pending.remove(cid);
            respondError(cid, ErrorCode.ADAPTER_UNAVAILABLE);
            return Mono.empty();
        }
        Envelope env = Envelope.builder()
                .profileId(d.profileId())
                .instanceId(session.instanceId())
                .processGeneration(generation)
                .connectionId(connectionId)
                .leaseId(leaseId)
                .fencingToken(fencingToken)
                .sequence(sequence.incrementAndGet())
                .correlationId(connectionId + "/" + cid)
                .messageKind(MessageKind.REQUEST)
                .operation(op)
                .deadline(deadline)
                .payload(payload)
                .build();
        Schedulers.parallel().schedule(() -> onDeadline(cid), requestDeadline.toMillis(), TimeUnit.MILLISECONDS);
        return client.send(env).onErrorResume(ProtocolException.class, e -> {
            if (pending.remove(cid) != null) {
                respondError(cid, e.code());
            }
            return Mono.empty();
        });
    }

    // ------------------------------------------------------------------ MessagePort relay (M3)

    private static final char WEB = 'w';
    private static final char NATIVE = 'n';

    /** 登记 payload 里新出现的端口（全部校验通过才登记，避免半登记）。 */
    private void openPorts(java.util.List<String> ids, char side, String parent) {
        for (String id : ids) {
            if (id.charAt(0) != side) {
                throw new ProtocolException(ErrorCode.PORT_STATE, "port id outside the sender's id space");
            }
            if (ports.get(id) != null) {
                throw new ProtocolException(ErrorCode.PORT_STATE, "port id already used");
            }
        }
        for (String id : ids) {
            ports.open(id, connectionId, parent);
        }
    }

    private String portOf(JsonNode frame) {
        JsonNode p = frame.get("port");
        if (p == null || !p.isString() || !PORT_ID.matcher(p.stringValue()).matches()) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "port id");
        }
        return p.stringValue();
    }

    private Mono<Void> onPortData(JsonNode frame) {
        String port;
        String payload;
        java.util.List<String> nested;
        try {
            port = portOf(frame);
            JsonNode p = frame.get("payload");
            if (p == null || !p.isString() || frame.get("op") != null || frame.get("cid") != null) {
                throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "port frame shape");
            }
            payload = p.stringValue();
            PortTable.PortInfo info = ports.get(port);
            if (info != null && info.state() == PortTable.State.CLOSED) {
                return Mono.empty(); // 与关闭交错的迟到帧：丢弃（关闭已通知双方）
            }
            ports.data(port, connectionId);
            nested = codec.portIds(payload);
            openPorts(nested, WEB, port);
        } catch (ProtocolException e) {
            if (e.code() == ErrorCode.QUEUE_LIMIT) {
                closePolicy(ErrorCode.QUEUE_LIMIT);
            } else {
                sendConnectionError(e.code());
            }
            return Mono.empty();
        }
        return forwardPort(MessageKind.PORT_DATA, port, payload);
    }

    private Mono<Void> onPortClose(JsonNode frame) {
        String port;
        try {
            port = portOf(frame);
            if (frame.get("payload") != null || frame.get("op") != null || frame.get("cid") != null) {
                throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "port frame shape");
            }
            if (!ports.close(port, connectionId)) {
                return Mono.empty(); // 已关闭：幂等
            }
        } catch (ProtocolException e) {
            sendConnectionError(e.code());
            return Mono.empty();
        }
        return forwardPort(MessageKind.PORT_CLOSE, port, "");
    }

    private Mono<Void> forwardPort(MessageKind kind, String port, String payload) {
        ProcessAdapterClient client = runtime.client();
        if (client == null || !runtime.ready()) {
            sendConnectionError(ErrorCode.ADAPTER_UNAVAILABLE);
            return Mono.empty();
        }
        Envelope env = Envelope.builder()
                .profileId(runtime.descriptor().profileId())
                .instanceId(session.instanceId())
                .processGeneration(generation)
                .connectionId(connectionId)
                .leaseId(leaseId)
                .fencingToken(fencingToken)
                .streamId(port)
                .sequence(sequence.incrementAndGet())
                .messageKind(kind)
                .deadline(clock.instant().plus(requestDeadline))
                .payload(payload)
                .build();
        return client.send(env).onErrorResume(ProtocolException.class, e -> {
            if (e.code() == ErrorCode.QUEUE_LIMIT) {
                closePolicy(ErrorCode.QUEUE_LIMIT);
            } else {
                sendConnectionError(e.code());
            }
            return Mono.empty();
        });
    }

    /** 闲置回收（MaintenanceSweeper 调用）：超时端口两侧都收到 PORT_CLOSE。 */
    void reapIdlePorts() {
        if (closed.get()) {
            return;
        }
        for (String id : ports.reapIdle()) {
            ObjectNode f = StrictJson.WIRE.object();
            f.put("kind", "PORT_CLOSE");
            f.put("port", id);
            outbox.offer(StrictJson.WIRE.write(f), Lane.CONTROL, null);
            forwardPort(MessageKind.PORT_CLOSE, id, "").subscribe();
        }
    }

    private void deliverPort(Envelope e) {
        String port = e.streamId();
        if (port == null || !PORT_ID.matcher(port).matches()) {
            return;
        }
        PortTable.PortInfo info = ports.get(port);
        if (info == null || info.state() != PortTable.State.OPEN) {
            return; // 未知 / 已关闭端口：丢弃（不接到新端口上）
        }
        ObjectNode f = StrictJson.WIRE.object();
        f.put("port", port);
        if (e.messageKind() == MessageKind.PORT_CLOSE) {
            ports.close(port, connectionId);
            f.put("kind", "PORT_CLOSE");
            outbox.offer(StrictJson.WIRE.write(f), Lane.CONTROL, null);
            return;
        }
        try {
            ports.data(port, connectionId);
            openPorts(codec.portIds(e.payload()), NATIVE, port);
        } catch (ProtocolException ex) {
            audit.record(session.id(), "port.reject", instanceId(), ex.code().name(), "connection=" + connectionId);
            if (ex.code() == ErrorCode.QUEUE_LIMIT) {
                closePolicy(ErrorCode.QUEUE_LIMIT);
            }
            return;
        }
        f.put("kind", "PORT_DATA");
        f.put("payload", e.payload());
        outbox.offer(StrictJson.WIRE.write(f), Lane.DATA, null);
    }

    /** 适配器响应 / 事件里的端口引用（原生侧创建，必须是 n 空间）；不合规的帧整帧丢弃。 */
    private boolean adoptNativePorts(String payload) {
        try {
            openPorts(codec.portIds(payload), NATIVE, null);
            return true;
        } catch (ProtocolException ex) {
            audit.record(session.id(), "port.reject", instanceId(), ex.code().name(), "connection=" + connectionId);
            return false;
        }
    }

    private void onDeadline(String cid) {
        if (pending.remove(cid) != null) {
            respondError(cid, ErrorCode.DEADLINE_EXCEEDED);
        }
    }

    // ------------------------------------------------------------------ outbound (adapter → browser)

    void deliver(Envelope e) {
        if (closed.get()) {
            return;
        }
        switch (e.messageKind()) {
            case RESPONSE -> {
                String prefix = connectionId + "/";
                String corr = e.correlationId();
                if (corr == null || !corr.startsWith(prefix)) {
                    return;
                }
                String cid = corr.substring(prefix.length());
                if (pending.remove(cid) == null) {
                    return; // 超时后迟到的结果：丢弃，浏览器已收到 DEADLINE_EXCEEDED（执行状态未知）
                }
                ObjectNode f = StrictJson.WIRE.object();
                f.put("kind", "RESPONSE");
                f.put("cid", cid);
                if (e.error() != null) {
                    putError(f, e.error().code());
                } else if (!adoptNativePorts(e.payload())) {
                    putError(f, ErrorCode.PORT_STATE);
                } else {
                    f.put("payload", e.payload());
                }
                outbox.offer(StrictJson.WIRE.write(f), Lane.DATA, null);
            }
            case EVENT -> {
                AdapterDescriptor d = runtime.descriptor();
                if (!d.eventAllowed(e.operation())) {
                    return; // 未声明 / 能力未开启的推送不进浏览器
                }
                if (!adoptNativePorts(e.payload())) {
                    return;
                }
                ObjectNode f = StrictJson.WIRE.object();
                f.put("kind", "EVENT");
                f.put("op", e.operation());
                f.put("payload", e.payload());
                outbox.offer(StrictJson.WIRE.write(f), Lane.DATA, d.coalescible(e.operation()) ? e.operation() : null);
            }
            case PORT_DATA, PORT_CLOSE -> deliverPort(e);
            case CONTROL -> {
                if (CLOSE_OPERATION.equals(e.operation())) {
                    closePolicy(e.error() != null && e.error().code() != null ? e.error().code() : ErrorCode.QUEUE_LIMIT);
                }
            }
            default -> {
                // HEARTBEAT / PORT_OPEN / PORT_TRANSFER / PORT_ERROR：浏览器中继不使用
            }
        }
    }

    private void respondError(String cid, ErrorCode code) {
        ObjectNode f = StrictJson.WIRE.object();
        f.put("kind", "RESPONSE");
        f.put("cid", cid);
        putError(f, code);
        outbox.offer(StrictJson.WIRE.write(f), Lane.CONTROL, null);
    }

    private void sendConnectionError(ErrorCode code) {
        ObjectNode f = StrictJson.WIRE.object();
        f.put("kind", "ERROR");
        putError(f, code);
        outbox.offer(StrictJson.WIRE.write(f), Lane.CONTROL, null);
    }

    private static void putError(ObjectNode f, ErrorCode code) {
        ObjectNode err = f.putObject("error");
        err.put("code", code.name());
        err.put("message", code.summary());
    }

    // ------------------------------------------------------------------ closing

    /** 撤销 / 失效：发送关闭帧（1008 + 错误码）后关闭。 */
    public void closePolicy(ErrorCode reason) {
        close(new CloseStatus(CLOSE_POLICY, reason.name()), reason, false);
    }

    /** 出站缓冲超预算：慢客户端直接断开（关闭帧本身也发不出去），不影响其他连接。 */
    private void onSlowConsumer() {
        close(new CloseStatus(CLOSE_POLICY, ErrorCode.QUEUE_LIMIT.name()), ErrorCode.QUEUE_LIMIT, true);
    }

    void close(CloseStatus status, ErrorCode reason, boolean abort) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        suspendedAt = null;
        closeReason = reason;
        pending.clear();
        releasePorts();
        String detail = "connection=" + connectionId;
        if (abort && outbox.overflowed()) {
            detail += " " + outbox.overflowDetail();
        }
        audit.record(session.id(), "transport.close", instanceId(), reason == null ? "OK" : reason.name(), detail);
        outbox.complete();
        ws.close(status).onErrorResume(t -> Mono.empty()).subscribe();
        if (abort) {
            MmabSocketAbort.abort(ws);
        }
    }

    /** 关闭本连接全部端口，并通知适配器释放该连接的原生侧状态（端口、订阅）。 */
    private void releasePorts() {
        ports.closeAll();
        ProcessAdapterClient client = runtime.client();
        if (client == null || runtime.descriptor() == null) {
            return;
        }
        try {
            client.sendControl(Envelope.builder()
                    .profileId(runtime.descriptor().profileId())
                    .instanceId(session.instanceId())
                    .processGeneration(generation)
                    .connectionId(connectionId)
                    .leaseId(leaseId)
                    .fencingToken(fencingToken)
                    .sequence(sequence.incrementAndGet())
                    .messageKind(MessageKind.CONTROL)
                    .operation(CLOSE_OPERATION)
                    .deadline(clock.instant().plus(Duration.ofSeconds(30)))
                    .build());
        } catch (RuntimeException ignored) {
            // 适配器已不在：其原生侧状态随进程消失
        }
    }

    /**
     * 浏览器侧关闭 / 网络断开（reliable-personal-entry D2）：连接进入挂起，端口、原生侧状态与出站缓冲保留
     * {@link #RESUME_GRACE}；同一页面在宽限期内以 {@code ?resume=<connectionId>} 重连即无损恢复。
     * 超时由 {@link TransportRegistry#sweepSuspended()} 正式关闭。
     *
     * @param ended 终止的是哪个传输（防止旧传输的迟到回调挂起已恢复的连接）
     */
    synchronized void onTransportTerminated(WebSocketSession ended) {
        onTransportTerminated(ended, 1006);
    }

    /**
     * @param peerCode 对端关闭码：1000 / 1001（页面主动关闭 / 离开）直接正式关闭；其他（含无关闭帧的 1006）挂起
     */
    synchronized void onTransportTerminated(WebSocketSession ended, int peerCode) {
        if (closed.get() || ended != ws) {
            return;
        }
        if (peerCode == 1000 || peerCode == 1001) {
            if (closed.compareAndSet(false, true)) {
                suspendedAt = null;
                pending.clear();
                releasePorts();
                outbox.complete();
                audit.record(session.id(), "transport.close", instanceId(), "OK",
                        "connection=" + connectionId + " peer=" + peerCode);
            }
            return;
        }
        if (suspendedAt != null) {
            return;
        }
        suspendedAt = clock.instant();
        pending.clear(); // 在途请求的结果对浏览器而言已不可知（不重放）
        leases.touch(session.instanceId(), leaseId, fencingToken);
        audit.record(session.id(), "transport.suspend", instanceId(), "OK", "connection=" + connectionId);
    }

    /** 兼容旧调用：以当前传输为准。 */
    void onTransportTerminated() {
        onTransportTerminated(ws);
    }

    /**
     * 平台侧保活（MaintenanceSweeper 每次清扫调用）：连接打开且最近 {@link #LIVENESS} 内收到过浏览器帧时续租约。
     * 挂起中的连接在宽限期内同样续租约（见 {@link #expireIfSuspendedLongerThan}）。
     */
    void keepLeaseAlive(Duration grace) {
        java.time.Instant last = lastInbound;
        if (closed.get()) {
            return;
        }
        java.time.Instant at = suspendedAt;
        if (at != null) {
            if (clock.instant().isBefore(at.plus(grace))) {
                leases.touch(session.instanceId(), leaseId, fencingToken); // 挂起等待恢复期间保住租约
            }
            return;
        }
        java.time.Instant now = clock.instant();
        if (last == null) {
            lastInbound = now; // 刚建立
            last = now;
        }
        if (now.isBefore(last.plus(LIVENESS))) {
            leases.touch(session.instanceId(), leaseId, fencingToken);
        }
    }

    public boolean suspended() {
        return suspendedAt != null && !closed.get();
    }

    /** 宽限期已过的挂起连接：正式关闭（释放端口、通知适配器）。 */
    boolean expireIfSuspendedLongerThan(Duration grace) {
        java.time.Instant at = suspendedAt;
        if (at == null || closed.get() || clock.instant().isBefore(at.plus(grace))) {
            if (at != null && !closed.get()) {
                leases.touch(session.instanceId(), leaseId, fencingToken); // 宽限期内保住租约
            }
            return false;
        }
        if (closed.compareAndSet(false, true)) {
            suspendedAt = null;
            releasePorts();
            outbox.complete();
            audit.record(session.id(), "transport.close", instanceId(), "OK",
                    "connection=" + connectionId + " cause=RESUME_EXPIRED");
        }
        return true;
    }

    /** 新页面已建立连接：挂起中的旧连接立即正式关闭（不再可恢复）。 */
    void discardSuspended() {
        if (closed.compareAndSet(false, true)) {
            suspendedAt = null;
            releasePorts();
            outbox.complete();
            audit.record(session.id(), "transport.close", instanceId(), "OK",
                    "connection=" + connectionId + " cause=SUPERSEDED");
        }
    }

    /**
     * 同一页面恢复：会话、租约绑定与 generation 仍有效时换上新的传输。
     *
     * @return 是否恢复成功
     */
    synchronized boolean resume(WebSocketSession next, InstanceSession who) {
        if (closed.get() || suspendedAt == null || who != session
                || access.check(session, leaseId, fencingToken, generation) != null) {
            return false;
        }
        long gap = java.time.Duration.between(suspendedAt, clock.instant()).toMillis();
        suspendedAt = null;
        ws = next;
        audit.record(session.id(), "transport.resume", instanceId(), "OK",
                "connection=" + connectionId + " gapMs=" + gap);
        return true;
    }
}
