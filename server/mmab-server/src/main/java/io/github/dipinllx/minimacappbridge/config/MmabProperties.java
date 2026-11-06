package io.github.dipinllx.minimacappbridge.config;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mmab.*} 配置（设计文档 §5.1、§15）。实例与端口静态绑定；端口冲突在启动时拒绝，
 * 不自动换端口。端口 {@code 0} 只用于测试（随机端口），不参与冲突检查。
 *
 * @param platform  平台元信息
 * @param console   控制台监听
 * @param instances 已登记实例
 * @param web       Web 安全与入口设置
 * @param timeouts  票据 / 会话 / 租约等时限
 * @param adapters  已登记的适配器包（命令来自静态配置，不来自 API）
 * @param operator  平台操作者
 * @param testing   仅测试使用的开关
 * @param home      仓库 / 安装根目录，适配器与资源相对路径以此为基准
 */
@ConfigurationProperties(prefix = "mmab")
public record MmabProperties(Platform platform, Console console, List<Instance> instances, Web web,
        Timeouts timeouts, List<AdapterPackage> adapters, Operator operator, Testing testing, String home) {

    public MmabProperties {
        platform = platform != null ? platform : new Platform("unknown");
        console = console != null ? console : new Console(8443, "127.0.0.1");
        instances = instances != null ? List.copyOf(instances) : List.of();
        web = web != null ? web : new Web(null, null, null, null);
        timeouts = timeouts != null ? timeouts : new Timeouts(null, null, null, null, null, null, null);
        adapters = adapters != null ? List.copyOf(adapters) : List.of();
        operator = operator != null ? operator : new Operator(null);
        testing = testing != null ? testing : new Testing(false, false);
        home = home == null || home.isBlank() ? "." : home;

        Set<Integer> ports = new HashSet<>();
        if (console.port() != 0) {
            ports.add(console.port());
        }
        Set<String> ids = new HashSet<>();
        for (Instance i : instances) {
            if (!ids.add(i.id())) {
                throw new IllegalArgumentException("duplicate mmab instance id: " + i.id());
            }
            if (i.port() != 0 && !ports.add(i.port())) {
                throw new IllegalArgumentException("port " + i.port() + " of instance " + i.id()
                        + " collides with console or another instance");
            }
        }
        Set<String> appIds = new HashSet<>();
        for (AdapterPackage a : adapters) {
            if (!appIds.add(a.appId())) {
                throw new IllegalArgumentException("duplicate mmab adapter package: " + a.appId());
            }
        }
    }

    /** 公开地址列表（用于 Host / Origin 精确匹配），默认只有绑定地址。 */
    public List<String> publicHosts() {
        return web.publicHosts().isEmpty() ? List.of(console.bindAddress()) : web.publicHosts();
    }

    /** @param version 构建版本 */
    public record Platform(String version) {
    }

    /**
     * @param port        控制台 HTTPS 端口（建议 8443）
     * @param bindAddress 精确绑定地址；不得为 0.0.0.0（所有实例监听同样绑定此地址）
     */
    public record Console(int port, String bindAddress) {
        public Console {
            requirePort(port, "mmab.console.port");
            if (bindAddress == null || bindAddress.isBlank()) {
                bindAddress = "127.0.0.1";
            }
            if ("0.0.0.0".equals(bindAddress) || "::".equals(bindAddress) || "*".equals(bindAddress)) {
                throw new IllegalArgumentException("mmab.console.bind-address must be an exact address, not a wildcard");
            }
        }
    }

    /**
     * @param id          实例 ID，例如 {@code qwen-01}
     * @param appId       适配器 appId，对应 {@code adapters/<appId>/adapter.yaml}
     * @param displayName 展示名
     * @param port        静态绑定端口（建议 8444-8459）
     * @param strategy    策略族 ID（须与 adapter.yaml 一致）
     * @param enabled     是否开监听并启动适配器
     */
    public record Instance(String id, String appId, String displayName, int port, String strategy, boolean enabled) {
        public Instance {
            if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,31}")) {
                throw new IllegalArgumentException("invalid mmab instance id: " + id);
            }
            requirePort(port, "mmab.instances[" + id + "].port");
        }
    }

    /**
     * @param publicHosts      浏览器访问用的主机（LAN IP / 127.0.0.1），Host 与 Origin 只接受这些主机 + 监听端口
     * @param tlsBundle        Spring SSL bundle 名；为空时只允许回环绑定的明文 HTTP（开发 / 测试）
     * @param consoleStaticDir 控制台构建产物目录（可选，相对 home）
     * @param webSdkDir        浏览器侧 SDK（shim / codec）目录，相对 home
     */
    public record Web(List<String> publicHosts, String tlsBundle, String consoleStaticDir, String webSdkDir) {
        public Web {
            publicHosts = publicHosts != null ? List.copyOf(publicHosts) : List.of();
            for (String h : publicHosts) {
                if (h == null || !h.matches("[A-Za-z0-9.-]{1,253}")) {
                    throw new IllegalArgumentException("invalid mmab.web.public-hosts entry");
                }
            }
            tlsBundle = tlsBundle == null || tlsBundle.isBlank() ? null : tlsBundle;
            consoleStaticDir = consoleStaticDir == null || consoleStaticDir.isBlank() ? null : consoleStaticDir;
            webSdkDir = webSdkDir == null || webSdkDir.isBlank() ? "sdk/web" : webSdkDir;
        }
    }

    /**
     * 时限默认值取自设计 §5.3（票据 30 秒）、§6.1（会话 8 小时 / 闲置 30 分钟）、§9.1（租约 60 秒）。
     */
    public record Timeouts(Duration ticketTtl, Duration leaseTtl, Duration sessionMaxAge, Duration sessionIdle,
            Duration requestDeadline, Duration sweepInterval, Duration adapterStartup) {
        public Timeouts {
            ticketTtl = orDefault(ticketTtl, Duration.ofSeconds(30));
            leaseTtl = orDefault(leaseTtl, Duration.ofSeconds(60));
            sessionMaxAge = orDefault(sessionMaxAge, Duration.ofHours(8));
            sessionIdle = orDefault(sessionIdle, Duration.ofMinutes(30));
            requestDeadline = orDefault(requestDeadline, Duration.ofSeconds(30));
            sweepInterval = orDefault(sweepInterval, Duration.ofSeconds(1));
            adapterStartup = orDefault(adapterStartup, Duration.ofSeconds(15));
        }

        private static Duration orDefault(Duration d, Duration def) {
            return d == null || d.isNegative() || d.isZero() ? def : d;
        }
    }

    /**
     * @param appId      对应 adapter.yaml 的 appId
     * @param descriptor adapter.yaml 路径（相对 home）
     * @param command    适配器进程命令（静态白名单；不接受 API 输入）
     * @param resources  原版资源 / fixture 目录（相对 home）；adapter-bundled 时由平台直接提供
     */
    public record AdapterPackage(String appId, String descriptor, List<String> command, String resources) {
        public AdapterPackage {
            if (appId == null || !appId.matches("[a-z][a-z0-9-]{1,31}")) {
                throw new IllegalArgumentException("invalid mmab adapter appId");
            }
            command = command != null ? List.copyOf(command) : List.of();
        }
    }

    /**
     * @param passwordHash 操作者口令的 PBKDF2 hash（见 {@code PasswordHasher}）；为空时控制台登录返回 SETUP_REQUIRED
     */
    public record Operator(String passwordHash) {
        public Operator {
            passwordHash = passwordHash == null || passwordHash.isBlank() ? null : passwordHash;
        }
    }

    /**
     * @param allowTestAdapters 允许启动 strategy=test-mock 的离线测试适配器
     * @param clockControl      允许测试推进平台时钟
     */
    public record Testing(boolean allowTestAdapters, boolean clockControl) {
    }

    private static void requirePort(int port, String name) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(name + " out of range: " + port);
        }
    }
}
