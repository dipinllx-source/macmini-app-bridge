package io.github.dipinllx.macminiappbridge.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 * @param dataDir   用户数据目录（运行时从本机 App 包提取的原版资源、适配器状态）；不在仓库内、不进 Git。
 *                  {@code mmab.adapters[].resources} 以 {@code data:} 开头时相对此目录解析
 */
@ConfigurationProperties(prefix = "mmab")
public record MmabProperties(Platform platform, Console console, List<Instance> instances, Web web,
        Timeouts timeouts, List<AdapterPackage> adapters, Operator operator, Testing testing, String home,
        String dataDir) {

    /** 精确 IPv4 字面量。 */
    static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    /** IPv6 字面量（不带方括号、不带 zone）。 */
    static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    /** M1 兼容构造器（无 dataDir）。 */
    public MmabProperties(Platform platform, Console console, List<Instance> instances, Web web, Timeouts timeouts,
            List<AdapterPackage> adapters, Operator operator, Testing testing, String home) {
        this(platform, console, instances, web, timeouts, adapters, operator, testing, home, null);
    }

    @ConstructorBinding
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
        dataDir = dataDir == null || dataDir.isBlank()
                ? System.getProperty("user.home") + "/Library/Application Support/MacminiAppBridge" : dataDir;
        syncPublicHosts(console, web);

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

    /** 公开地址列表（用于 Host / Origin 精确匹配），默认等于全部绑定地址（IPv6 带方括号）。 */
    public List<String> publicHosts() {
        return web.publicHosts().isEmpty() ? console.bindAddresses().stream().map(MmabProperties::hostForm).toList()
                : web.publicHosts();
    }

    /**
     * 设计 §5.1：监听只绑精确 LAN IP 与 127.0.0.1，Host / Origin 精确匹配。两者必须同步：
     * 每个绑定地址都必须出现在 public-hosts 中（否则从该地址来的请求全被拒，配置自相矛盾）；
     * public-hosts 中的每个 IP 字面量都必须是绑定地址（否则平台会接受一个自己并不监听的 Origin）。
     */
    private static void syncPublicHosts(Console console, Web web) {
        if (web.publicHosts().isEmpty()) {
            return;
        }
        Set<String> bound = new HashSet<>();
        for (String a : console.bindAddresses()) {
            bound.add(normalizeLiteral(a));
        }
        Set<String> listed = new HashSet<>();
        for (String h : web.publicHosts()) {
            String literal = literalOf(h);
            if (literal == null) {
                continue; // 主机名（例如将来的内网 DNS 名）不参与地址同步
            }
            String n = normalizeLiteral(literal);
            if (!bound.contains(n)) {
                throw new IllegalArgumentException("mmab.web.public-hosts lists an address that is not a bind address");
            }
            listed.add(n);
        }
        if (!listed.containsAll(bound)) {
            throw new IllegalArgumentException("mmab.web.public-hosts must include every bind address");
        }
    }

    /** 若 public host 是 IP 字面量（IPv4 或 [IPv6]），返回去掉方括号的字面量；主机名返回 null。 */
    public static String literalOf(String publicHost) {
        if (publicHost.startsWith("[") && publicHost.endsWith("]")) {
            return publicHost.substring(1, publicHost.length() - 1);
        }
        return IPV4.matcher(publicHost).matches() ? publicHost : null;
    }

    /** 绑定地址 → Host / Origin 中的写法（IPv6 加方括号）。 */
    public static String hostForm(String bindAddress) {
        return bindAddress.contains(":") ? "[" + bindAddress + "]" : bindAddress;
    }

    /** IP 字面量规范化（不做 DNS 解析：调用前已用正则确认是字面量）。 */
    public static String normalizeLiteral(String literal) {
        if (!IPV4.matcher(literal).matches() && !IPV6.matcher(literal).matches()) {
            throw new IllegalArgumentException("not an IP literal");
        }
        try {
            return InetAddress.getByName(literal).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not an IP literal", e);
        }
    }

    /** @param version 构建版本 */
    public record Platform(String version) {
    }

    /**
     * 监听地址（设计 §5.1）：控制台与每个实例端口都绑定 {@code bindAddresses} 中的每个精确地址，
     * 典型值为 {@code [<LAN IP>, 127.0.0.1]}；绝不允许通配地址。
     *
     * @param port          控制台 HTTPS 端口（建议 8443）
     * @param bindAddress   M1 单地址写法；{@code bindAddresses} 为空时使用
     * @param bindAddresses 精确地址列表（IP 字面量，第一个为主地址）；非空时优先于 {@code bindAddress}
     */
    public record Console(int port, String bindAddress, List<String> bindAddresses) {

        /** M1 兼容构造器：单一绑定地址。 */
        public Console(int port, String bindAddress) {
            this(port, bindAddress, null);
        }

        @ConstructorBinding
        public Console {
            requirePort(port, "mmab.console.port");
            if (bindAddress == null || bindAddress.isBlank()) {
                bindAddress = "127.0.0.1";
            }
            List<String> raw = bindAddresses == null || bindAddresses.isEmpty() ? List.of(bindAddress) : bindAddresses;
            Set<String> seen = new LinkedHashSet<>();
            List<String> exact = new ArrayList<>();
            for (String a : raw) {
                String n = exactAddress(a);
                if (!seen.add(n)) {
                    throw new IllegalArgumentException("duplicate mmab.console.bind-addresses entry");
                }
                exact.add(a.trim());
            }
            bindAddresses = List.copyOf(exact);
            bindAddress = bindAddresses.get(0);
        }

        /** 是否全部为回环地址（只有这时才允许明文 HTTP）。 */
        public boolean allLoopback() {
            return bindAddresses.stream().allMatch(a -> {
                try {
                    return InetAddress.getByName(a).isLoopbackAddress();
                } catch (UnknownHostException e) {
                    return false;
                }
            });
        }

        private static String exactAddress(String a) {
            if (a == null || a.isBlank()) {
                throw new IllegalArgumentException("mmab.console.bind-addresses entry must not be blank");
            }
            String t = a.trim();
            if ("*".equals(t) || "0.0.0.0".equals(t) || "::".equals(t)) {
                throw new IllegalArgumentException("mmab.console.bind-address must be an exact address, not a wildcard");
            }
            String n;
            try {
                n = normalizeLiteral(t);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("mmab.console.bind-addresses entries must be IP literals", e);
            }
            try {
                InetAddress addr = InetAddress.getByName(n);
                if (addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                    throw new IllegalArgumentException(
                            "mmab.console.bind-address must be an exact address, not a wildcard");
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("unresolvable bind address", e);
            }
            return n;
        }
    }

    /**
     * @param id          实例 ID，例如 {@code qwen-01}
     * @param appId       适配器 appId，对应 {@code adapters/<appId>/adapter.yaml}
     * @param displayName 展示名
     * @param port        静态绑定端口（建议 8444-8459）
     * @param strategy    策略族 ID（须与 adapter.yaml 一致）
     * @param enabled     是否开监听并启动适配器
     * @param verifyCapabilities 操作者为验证 / 切换窗口显式开启的能力 ID（ADR-0005）：只能是 adapter.yaml 中
     *                    状态为 SUPPORTED、但平台路径尚无 live 证据的能力；启动时写审计
     */
    public record Instance(String id, String appId, String displayName, int port, String strategy, boolean enabled,
            List<String> verifyCapabilities) {

        /** M1 兼容构造器。 */
        public Instance(String id, String appId, String displayName, int port, String strategy, boolean enabled) {
            this(id, appId, displayName, port, strategy, enabled, null);
        }

        @ConstructorBinding
        public Instance {
            if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,31}")) {
                throw new IllegalArgumentException("invalid mmab instance id: " + id);
            }
            requirePort(port, "mmab.instances[" + id + "].port");
            verifyCapabilities = verifyCapabilities == null ? List.of() : List.copyOf(verifyCapabilities);
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
                if (h == null || !(h.matches("[A-Za-z0-9.-]{1,253}") || h.matches("\\[[0-9A-Fa-f:.]{2,45}\\]"))) {
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
     * @param resources  原版资源 / fixture 目录：相对 home；以 {@code data:} 开头时相对 {@code mmab.data-dir}
     *                   （运行时从本机 App 包提取的原版资源，绝不入库）
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
