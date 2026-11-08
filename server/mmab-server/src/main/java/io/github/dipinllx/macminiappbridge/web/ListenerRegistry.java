package io.github.dipinllx.macminiappbridge.web;

import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 实际绑定端口 → 监听角色（控制台 / 某实例）。请求的监听角色只按 socket 本地端口判定，
 * 不信任任何请求头（设计 §5.1：每个监听只服务一个已登记实例）。
 */
@Component
public class ListenerRegistry {

    /** 监听角色：控制台，或某个实例。 */
    public record Role(boolean console, String instanceId, int port) {
        public static Role console(int port) {
            return new Role(true, null, port);
        }

        public static Role instance(String id, int port) {
            return new Role(false, id, port);
        }
    }

    private final List<String> hosts;
    private final String scheme;
    private volatile int consolePort = -1;
    private final Map<Integer, String> instanceByPort = new ConcurrentHashMap<>();
    private final Map<String, Integer> portByInstance = new ConcurrentHashMap<>();

    public ListenerRegistry(MmabProperties properties) {
        this.hosts = properties.publicHosts();
        this.scheme = properties.web().tlsBundle() != null ? "https" : "http";
    }

    @EventListener
    public void onConsoleStarted(ReactiveWebServerInitializedEvent event) {
        registerConsole(event.getWebServer().getPort());
    }

    public void registerConsole(int port) {
        this.consolePort = port;
    }

    public void registerInstance(String instanceId, int port) {
        instanceByPort.put(port, instanceId);
        portByInstance.put(instanceId, port);
    }

    public void unregisterInstance(String instanceId) {
        Integer port = portByInstance.remove(instanceId);
        if (port != null) {
            instanceByPort.remove(port);
        }
    }

    public Optional<Role> resolve(int localPort) {
        if (localPort == consolePort) {
            return Optional.of(Role.console(localPort));
        }
        String id = instanceByPort.get(localPort);
        return id == null ? Optional.empty() : Optional.of(Role.instance(id, localPort));
    }

    public int consolePort() {
        return consolePort;
    }

    public Optional<Integer> instancePort(String instanceId) {
        return Optional.ofNullable(portByInstance.get(instanceId));
    }

    /** 已绑定的实例端口。 */
    public java.util.Collection<Integer> registeredInstancePorts() {
        return java.util.List.copyOf(portByInstance.values());
    }

    public String scheme() {
        return scheme;
    }

    public List<String> hosts() {
        return hosts;
    }

    /** Host 头必须精确等于 {@code <登记主机>:<该监听端口>}（防 DNS rebinding）。 */
    public boolean hostAllowed(String hostHeader, int port) {
        if (hostHeader == null) {
            return false;
        }
        for (String h : hosts) {
            if ((h + ":" + port).equalsIgnoreCase(hostHeader)) {
                return true;
            }
        }
        return false;
    }

    /** {@code origin} 是否恰为 {@code scheme://<登记主机>:<port>}。 */
    public boolean isOriginFor(String origin, int port) {
        return originHost(origin, port).isPresent();
    }

    /** 若 origin 精确匹配某登记主机 + 端口，返回该主机。缺失 / "null" / 带路径一律不匹配。 */
    public Optional<String> originHost(String origin, int port) {
        if (origin == null || port <= 0) {
            return Optional.empty();
        }
        for (String h : hosts) {
            if (origin.equals(scheme + "://" + h + ":" + port)) {
                return Optional.of(h);
            }
        }
        return Optional.empty();
    }

    public boolean isConsoleOrigin(String origin) {
        return isOriginFor(origin, consolePort);
    }

    public String origin(String host, int port) {
        return scheme + "://" + host + ":" + port;
    }
}
