package io.github.dipinllx.minimacappbridge.proxy;

import io.github.dipinllx.minimacappbridge.audit.AuditLog;
import io.github.dipinllx.minimacappbridge.config.MmabClock;
import io.github.dipinllx.minimacappbridge.config.MmabProperties;
import io.github.dipinllx.minimacappbridge.identity.InstanceSession;
import io.github.dipinllx.minimacappbridge.identity.Tokens;
import io.github.dipinllx.minimacappbridge.instances.InstanceAccess;
import io.github.dipinllx.minimacappbridge.instances.InstanceRegistry;
import io.github.dipinllx.minimacappbridge.instances.InstanceRuntime;
import io.github.dipinllx.minimacappbridge.leases.LeaseManager;
import io.github.dipinllx.minimacappbridge.protocol.ErrorCode;
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

    public BridgeSocketHandler(InstanceRegistry instances, TransportRegistry transports, InstanceAccess access,
            LeaseManager leases, AuditLog audit, MmabClock clock, MmabProperties properties) {
        this.instances = instances;
        this.transports = transports;
        this.access = access;
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
        this.requestDeadline = properties.timeouts().requestDeadline();
    }

    public Mono<Void> serve(WebSocketSession ws, InstanceSession session) {
        InstanceRuntime rt = instances.get(session.instanceId()).orElse(null);
        if (rt == null || !rt.ready() || rt.descriptor() == null) {
            return ws.close(new CloseStatus(1011, ErrorCode.ADAPTER_UNAVAILABLE.name()));
        }
        ErrorCode invalid = access.check(session);
        if (invalid != null) {
            return ws.close(new CloseStatus(BridgeConnection.CLOSE_POLICY, invalid.name()));
        }
        BridgeConnection c = new BridgeConnection(Tokens.shortId("c-"), session, rt, ws, access, leases, audit, clock,
                requestDeadline);
        if (!transports.attach(c)) {
            audit.record(session.id(), "transport.open", session.instanceId(), ErrorCode.LEASE_OCCUPIED.name(), null);
            return ws.close(new CloseStatus(BridgeConnection.CLOSE_OCCUPIED, ErrorCode.LEASE_OCCUPIED.name()));
        }
        audit.record(session.id(), "transport.open", session.instanceId(), "OK", "connection=" + c.connectionId());
        Mono<Void> out = c.outbox().flux()
                .concatMap(text -> ws.send(Mono.just(ws.textMessage(text))), 1)
                .then();
        Mono<Void> in = ws.receive()
                .map(BridgeConnection.Inbound::of)
                .concatMap(c::onInbound, 1)
                .then()
                .doFinally(sig -> c.onTransportTerminated());
        return Mono.when(in, out)
                .onErrorResume(t -> Mono.empty())
                .doFinally(sig -> {
                    c.onTransportTerminated();
                    transports.detach(c);
                });
    }
}
