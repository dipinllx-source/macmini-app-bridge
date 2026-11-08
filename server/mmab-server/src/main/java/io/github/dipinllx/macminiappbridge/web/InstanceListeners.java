package io.github.dipinllx.macminiappbridge.web;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.instances.InstanceRegistry;
import io.github.dipinllx.macminiappbridge.instances.InstanceRuntime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.stereotype.Component;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.server.HttpServer;

/**
 * 实例端口监听（设计 §5.1：一个 Java 进程多 HTTPS 监听，每个监听只服务一个已登记实例）。
 * 与控制台共用同一个 {@link HttpHandler}，监听角色由 {@link ListenerRegistry} 按本地端口判定。
 * 端口被占用时不抢占、不换端口：该实例进入 BLOCKED（PORT_OWNER_MISMATCH）。
 */
@Component
public class InstanceListeners implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InstanceListeners.class);

    private final MmabProperties properties;
    private final ObjectProvider<HttpHandler> httpHandler;
    private final ObjectProvider<SslBundles> sslBundles;
    private final ListenerRegistry registry;
    private final InstanceRegistry instances;
    private final AuditLog audit;
    private final List<DisposableServer> servers = new ArrayList<>();
    private volatile boolean running;

    public InstanceListeners(MmabProperties properties, ObjectProvider<HttpHandler> httpHandler,
            ObjectProvider<SslBundles> sslBundles, ListenerRegistry registry, InstanceRegistry instances,
            AuditLog audit) {
        this.properties = properties;
        this.httpHandler = httpHandler;
        this.sslBundles = sslBundles;
        this.registry = registry;
        this.instances = instances;
        this.audit = audit;
    }

    @Override
    public synchronized void start() {
        HttpHandler handler = httpHandler.getObject();
        ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(handler);
        String bundle = properties.web().tlsBundle();
        KeyManagerFactory kmf = bundle == null ? null
                : sslBundles.getObject().getBundle(bundle).getManagers().getKeyManagerFactory();
        for (InstanceRuntime rt : instances.all()) {
            if (!rt.config().enabled()) {
                continue;
            }
            HttpServer server = HttpServer.create()
                    .host(properties.console().bindAddress())
                    .port(rt.config().port())
                    .idleTimeout(Duration.ofMinutes(5))
                    .handle(adapter);
            if (kmf != null) {
                server = server.secure(spec -> spec.sslContext(Http11SslContextSpec.forServer(kmf)));
            }
            try {
                DisposableServer s = server.bindNow(Duration.ofSeconds(10));
                servers.add(s);
                int port = ((java.net.InetSocketAddress) s.address()).getPort();
                registry.registerInstance(rt.id(), port);
                audit.record(null, "listener.bind", rt.id(), "OK", "port=" + port);
            } catch (RuntimeException e) {
                instances.markPortBlocked(rt.id());
                audit.record(null, "listener.bind", rt.id(), "PORT_OWNER_MISMATCH", null);
                log.warn("instance {} port {} could not be bound; instance blocked", rt.id(), rt.config().port());
            }
        }
        running = true;
    }

    @Override
    public synchronized void stop() {
        for (DisposableServer s : servers) {
            s.disposeNow(Duration.ofSeconds(5));
        }
        servers.clear();
        for (InstanceRuntime rt : instances.all()) {
            registry.unregisterInstance(rt.id());
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 1024;
    }
}
