package io.github.dipinllx.minimacappbridge.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mmab.*} 配置（设计文档 §5.1、§15）。实例与端口静态绑定；端口冲突在启动时拒绝，
 * 不自动换端口。
 *
 * @param platform  平台元信息
 * @param console   控制台监听
 * @param instances 已登记实例（骨架阶段仅登记，不开监听）
 */
@ConfigurationProperties(prefix = "mmab")
public record MmabProperties(Platform platform, Console console, List<Instance> instances) {

    public MmabProperties {
        platform = platform != null ? platform : new Platform("unknown");
        console = console != null ? console : new Console(8443, "127.0.0.1");
        instances = instances != null ? List.copyOf(instances) : List.of();

        Set<Integer> ports = new HashSet<>();
        ports.add(console.port());
        Set<String> ids = new HashSet<>();
        for (Instance i : instances) {
            if (!ids.add(i.id())) {
                throw new IllegalArgumentException("duplicate mmab instance id: " + i.id());
            }
            if (!ports.add(i.port())) {
                throw new IllegalArgumentException("port " + i.port() + " of instance " + i.id()
                        + " collides with console or another instance");
            }
        }
    }

    /** @param version 构建版本 */
    public record Platform(String version) {
    }

    /**
     * @param port        控制台 HTTPS 端口（建议 8443）
     * @param bindAddress 精确绑定地址；不得为 0.0.0.0
     */
    public record Console(int port, String bindAddress) {
        public Console {
            requirePort(port, "mmab.console.port");
            if (bindAddress == null || bindAddress.isBlank()) {
                bindAddress = "127.0.0.1";
            }
            if ("0.0.0.0".equals(bindAddress) || "::".equals(bindAddress)) {
                throw new IllegalArgumentException("mmab.console.bind-address must be an exact address, not a wildcard");
            }
        }
    }

    /**
     * @param id          实例 ID，例如 {@code qwen-01}
     * @param appId       适配器 appId，对应 {@code adapters/<appId>/adapter.yaml}
     * @param displayName 展示名
     * @param port        静态绑定端口（建议 8444-8459）
     * @param strategy    策略族 ID
     * @param enabled     是否开监听；骨架阶段恒为 false
     */
    public record Instance(String id, String appId, String displayName, int port, String strategy, boolean enabled) {
        public Instance {
            if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,31}")) {
                throw new IllegalArgumentException("invalid mmab instance id: " + id);
            }
            requirePort(port, "mmab.instances[" + id + "].port");
        }
    }

    private static void requirePort(int port, String name) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(name + " out of range: " + port);
        }
    }
}
