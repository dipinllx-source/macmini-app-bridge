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
 * </pre>
 *
 * <p>浏览器不提供 instance / generation / lease / fence：这些由服务端按会话填入 envelope，
 * 浏览器无法伪造 fence。
 */
public final class BridgeConnection {

    /** WS 关闭码：策略拒绝（失效 / 撤销 / 慢客户端）。 */
    public static final int CLOSE_POLICY = 1008;
    /** WS 关闭码：租约已被其他标签页占用（设计 §9.1：WS 1013）。 */
    public static final int CLOSE_OCCUPIED = 1013;

    private static final Pattern CID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> FRAME_KEYS = Set.of("kind", "op", "cid", "payload");

    private record Pending(String op, Instant deadline) {
    }

    private final String connectionId;
    private final InstanceSession session;
    private final InstanceRuntime runtime;
    private final WebSocketSession ws;
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
    private volatile ErrorCode closeReason;

    BridgeConnection(String connectionId, InstanceSession session, InstanceRuntime runtime, WebSocketSession ws,
            InstanceAccess access, LeaseManager leases, AuditLog audit, MmabClock clock, Duration requestDeadline) {
        this.connectionId = connectionId;
        this.session = session;
        this.runtime = runtime;
        this.ws = ws;
        this.access = access;
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
        this.requestDeadline = requestDeadline;
        this.outbox = new SessionOutbox(BoundedFrameQueue.Budget.outbound(), this::onSlowConsumer);
    }

    public String connectionId() {
        return connectionId;
    }

    public InstanceSession session() {
        return session;
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
        ErrorCode invalid = access.check(session);
        if (invalid != null) {
            closePolicy(invalid);
            return Mono.empty();
        }
        return switch (frame.get("kind").stringValue()) {
            case "HEARTBEAT" -> {
                leases.touch(session.instanceId(), session.leaseId(), session.fencingToken());
                ObjectNode ack = StrictJson.WIRE.object();
                ack.put("kind", "HEARTBEAT");
                outbox.offer(StrictJson.WIRE.write(ack), Lane.CONTROL, null);
                yield Mono.empty();
            }
            case "REQUEST" -> onRequest(frame);
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
            codec.validate(payload);
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
                .processGeneration(session.generation())
                .connectionId(connectionId)
                .leaseId(session.leaseId())
                .fencingToken(session.fencingToken())
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
                ObjectNode f = StrictJson.WIRE.object();
                f.put("kind", "EVENT");
                f.put("op", e.operation());
                f.put("payload", e.payload());
                outbox.offer(StrictJson.WIRE.write(f), Lane.DATA, d.coalescible(e.operation()) ? e.operation() : null);
            }
            default -> {
                // M1：端口类帧尚未接入浏览器中继
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
        closeReason = reason;
        pending.clear();
        audit.record(session.id(), "transport.close", instanceId(), reason == null ? "OK" : reason.name(),
                "connection=" + connectionId);
        outbox.complete();
        ws.close(status).onErrorResume(t -> Mono.empty()).subscribe();
        if (abort) {
            MmabSocketAbort.abort(ws);
        }
    }

    /** 浏览器侧关闭 / 网络断开。 */
    void onTransportTerminated() {
        if (closed.compareAndSet(false, true)) {
            pending.clear();
            outbox.complete();
            audit.record(session.id(), "transport.close", instanceId(), "OK", "connection=" + connectionId);
        }
    }
}
