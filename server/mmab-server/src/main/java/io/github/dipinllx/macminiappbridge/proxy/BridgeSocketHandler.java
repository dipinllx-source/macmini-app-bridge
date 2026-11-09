package io.github.dipinllx.macminiappbridge.proxy;

import java.util.Optional;
import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.identity.InstanceSession;
import io.github.dipinllx.macminiappbridge.identity.Tokens;
import io.github.dipinllx.macminiappbridge.instances.InstanceAccess;
import io.github.dipinllx.macminiappbridge.instances.InstanceRegistry;
import io.github.dipinllx.macminiappbridge.instances.InstanceRuntime;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * 实例端口 {@code /__mmab/bridge/ws} 的连接处理：校验会话 / 租约 / generation，一租约一连接，
 * 入站按需读取（单帧顺序处理，适配器队列高水位时停止读取 → TCP 背压），出站逐帧写出（字节全部计入出站预算）。
 */
@Component
public class BridgeSocketHandler {

    private final InstanceRegistry instances;
    private final TransportRegistry transports;
    private final InstanceAccess access;
    private final LeaseManager leases;
    private final AuditLog audit;
    private final MmabClock clock;
    private final Duration requestDeadline;

    private final BoundedFrameQueue.Budget outboundBudget;

    public BridgeSocketHandler(InstanceRegistry instances, TransportRegistry transports, InstanceAccess access,
            LeaseManager leases, AuditLog audit, MmabClock clock, MmabProperties properties) {
        this.instances = instances;
        this.transports = transports;
        this.access = access;
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
        this.requestDeadline = properties.timeouts().requestDeadline();
        this.outboundBudget = BoundedFrameQueue.Budget.outbound(properties.transport().outboundMaxFrames(),
                properties.transport().outboundMaxBytes());
    }

    public Mono<Void> serve(WebSocketSession ws, InstanceSession session) {
        InstanceRuntime rt = instances.get(session.instanceId()).orElse(null);
        if (rt == null || !rt.ready() || rt.descriptor() == null) {
            return ws.close(new CloseStatus(1011, ErrorCode.ADAPTER_UNAVAILABLE.name()));
        }
        ErrorCode invalid = access.ensureBound(session);
        if (invalid != null) {
            return ws.close(new CloseStatus(BridgeConnection.CLOSE_POLICY, invalid.name()));
        }
        // reliable-personal-entry D2：同一页面在宽限期内以 ?resume=<connectionId> 重连 → 换上新传输，端口与缓冲无损
        String resumeId = resumeParam(ws);
        Optional<BridgeConnection> resumable = transports.suspended(resumeId);
        BridgeConnection c;
        boolean resumed = false;
        if (resumable.isPresent() && resumable.get().resume(ws, session)) {
            c = resumable.get();
            resumed = true;
        } else {
            c = new BridgeConnection(Tokens.shortId("c-"), session, rt, ws, access, leases, audit, clock,
                    requestDeadline, outboundBudget);
            if (!transports.attach(c)) {
                audit.record(session.id(), "transport.open", session.instanceId(), ErrorCode.LEASE_OCCUPIED.name(), null);
                return ws.close(new CloseStatus(BridgeConnection.CLOSE_OCCUPIED, ErrorCode.LEASE_OCCUPIED.name()));
            }
            audit.record(session.id(), "transport.open", session.instanceId(), "OK",
                    "connection=" + c.connectionId() + (resumeId != null ? " resumeRefused=true" : ""));
        }
        // 第一帧告诉页面连接 ID 与是否无损恢复；未恢复时页面需要按应用自身方式重新初始化
        c.outbox().offer("{\"kind\":\"HELLO\",\"connectionId\":\"" + c.connectionId() + "\",\"resumed\":" + resumed + "}",
                BoundedFrameQueue.Lane.CONTROL, null);
        final BridgeConnection conn = c;
        Mono<Void> out = conn.outbox().flux()
                .concatMap(text -> ws.send(Mono.just(ws.textMessage(text))), 1)
                .then();
        // 对端关闭码决定“正式关闭”还是“挂起等待恢复”；没有关闭帧（网络中断）按 1006 处理。
        ws.closeStatus().map(CloseStatus::getCode).defaultIfEmpty(1006)
                .subscribe(code -> conn.onTransportTerminated(ws, code), t -> conn.onTransportTerminated(ws, 1006));
        Mono<Void> in = ws.receive()
                .map(BridgeConnection.Inbound::of)
                .concatMap(conn::onInbound, 1)
                .then();
        return Mono.when(in, out)
                .onErrorResume(t -> Mono.empty())
                .then(Mono.delay(java.time.Duration.ofMillis(300)).then()) // 让关闭码先到
                .doFinally(sig -> {
                    conn.onTransportTerminated(ws, 1006);
                    if (!conn.suspended() && conn.isClosed()) {
                        transports.detach(conn);
                    }
                });
    }

    private static String resumeParam(WebSocketSession ws) {
        String q = ws.getHandshakeInfo().getUri().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            if (part.startsWith("resume=")) {
                String v = part.substring("resume=".length());
                return v.matches("c-[A-Za-z0-9_-]{4,40}") ? v : null;
            }
        }
        return null;
    }
}
