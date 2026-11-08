package io.github.dipinllx.macminiappbridge.web;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.instances.InstanceRegistry;
import io.github.dipinllx.macminiappbridge.instances.InstanceRuntime;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerInitializedEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.stereotype.Component;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.server.HttpServer;

/**
 * 实例端口监听（设计 §5.1：一个 Java 进程多 HTTPS 监听，每个监听只服务一个已登记实例）。
 * 与控制台共用同一个 {@link HttpHandler}，监听角色由 {@link ListenerRegistry} 按本地端口判定。
 * 每个端口绑定 {@code mmab.console.bind-addresses} 中的每个精确地址（典型：LAN IP + 127.0.0.1），
 * 控制台主地址由 Spring 主服务器绑定，其余地址在主服务器启动后以同一端口补绑。
 * 端口被占用时不抢占、不换端口：该实例进入 BLOCKED（PORT_OWNER_MISMATCH），已绑上的其他地址一并释放。
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
        for (InstanceRuntime rt : instances.all()) {
            if (!rt.config().enabled()) {
                continue;
            }
            List<String> addresses = properties.console().bindAddresses();
            List<DisposableServer> bound = new ArrayList<>();
            try {
                int port = rt.config().port();
                for (String address : addresses) {
                    DisposableServer s = bind(address, port);
                    bound.add(s);
                    port = ((InetSocketAddress) s.address()).getPort(); // 端口 0（测试）：后续地址用同一实际端口
                }
                servers.addAll(bound);
                registry.registerInstance(rt.id(), port);
                audit.record(null, "listener.bind", rt.id(), "OK", "port=" + port + " addresses=" + addresses.size());
            } catch (RuntimeException e) {
                for (DisposableServer s : bound) {
                    s.disposeNow(Duration.ofSeconds(5));
                }
                instances.markPortBlocked(rt.id());
                audit.record(null, "listener.bind", rt.id(), "PORT_OWNER_MISMATCH", null);
                log.warn("instance {} port {} could not be bound on every address; instance blocked", rt.id(),
                        rt.config().port());
            }
        }
        running = true;
    }

    /** 控制台主地址由 Spring 主服务器绑定；其余精确地址以同一端口补绑，失败即启动失败（不静默少绑）。 */
    @EventListener
    public synchronized void onConsoleStarted(ReactiveWebServerInitializedEvent event) {
        List<String> addresses = properties.console().bindAddresses();
        int port = event.getWebServer().getPort();
        for (String address : addresses.subList(1, addresses.size())) {
            try {
                servers.add(bind(address, port));
            } catch (RuntimeException e) {
                audit.record(null, "listener.bind", "console", "PORT_OWNER_MISMATCH", null);
                throw new IllegalStateException("console port " + port + " could not be bound on every address", e);
            }
        }
        audit.record(null, "listener.bind", "console", "OK", "port=" + port + " addresses=" + addresses.size());
    }

    private DisposableServer bind(String address, int port) {
        String bundle = properties.web().tlsBundle();
        KeyManagerFactory kmf = bundle == null ? null
                : sslBundles.getObject().getBundle(bundle).getManagers().getKeyManagerFactory();
        HttpServer server = HttpServer.create()
                .host(address)
                .port(port)
                .idleTimeout(Duration.ofMinutes(5))
                .handle(new ReactorHttpHandlerAdapter(httpHandler.getObject()));
        if (kmf != null) {
            server = server.secure(spec -> spec.sslContext(Http11SslContextSpec.forServer(kmf)));
        }
        return server.bindNow(Duration.ofSeconds(10));
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
