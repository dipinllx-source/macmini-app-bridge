package io.github.dipinllx.macminiappbridge.api;

import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.instances.AdapterDescriptor;
import io.github.dipinllx.macminiappbridge.instances.InstanceRegistry;
import io.github.dipinllx.macminiappbridge.instances.InstanceRuntime;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.web.ApiException;
import io.github.dipinllx.macminiappbridge.web.ListenerRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 静态内容：控制台端口提供 Vue 构建产物（可选），实例端口提供原版资源 / fixture 与浏览器侧 shim。
 * 路径限制在资源根内（拒绝 {@code ..}、反斜杠、NUL，解析符号链接后仍须在根内，设计 §6.3）。
 * 实例首页注入 shim：{@code <script type="module" src="/__mmab/shim/shim.mjs">}（设计 §2.1：HTML 注入 shim）。
 */
@RestController
public class ContentController {

    static final String SHIM_TAG = "<script type=\"module\" src=\"/__mmab/shim/shim.mjs\"></script>";
    /** 单个原版资源上限：千问 2.0.0 的 vendor 包约 37 MiB（M3 实机发现 32 MiB 上限导致白屏）。 */
    private static final long MAX_FILE_BYTES = 96L * 1024 * 1024;

    /** 原版前端常见的其他静态类型（M3：真实应用资源）；未知类型仍为 octet-stream + nosniff。 */
    private static final java.util.Map<String, String> EXTRA_TYPES = java.util.Map.ofEntries(
            java.util.Map.entry("wasm", "application/wasm"), java.util.Map.entry("webp", "image/webp"),
            java.util.Map.entry("jpg", "image/jpeg"), java.util.Map.entry("jpeg", "image/jpeg"),
            java.util.Map.entry("gif", "image/gif"), java.util.Map.entry("woff", "font/woff"),
            java.util.Map.entry("ttf", "font/ttf"), java.util.Map.entry("otf", "font/otf"),
            java.util.Map.entry("mp3", "audio/mpeg"), java.util.Map.entry("wav", "audio/wav"), java.util.Map.entry("ogg", "audio/ogg"),
            java.util.Map.entry("mp4", "video/mp4"), java.util.Map.entry("webm", "video/webm"),
            java.util.Map.entry("txt", "text/plain;charset=UTF-8"), java.util.Map.entry("map", "application/json"),
            java.util.Map.entry("webmanifest", "application/manifest+json"), java.util.Map.entry("avif", "image/avif"));

    private final InstanceRegistry instances;
    private final ListenerRegistry listeners;
    private final Path home;
    private final Path consoleRoot;
    private final Path sdkRoot;

    public ContentController(InstanceRegistry instances, MmabProperties properties, ListenerRegistry listeners) {
        this.instances = instances;
        this.listeners = listeners;
        this.home = Path.of(properties.home()).toAbsolutePath().normalize();
        this.consoleRoot = properties.web().consoleStaticDir() == null ? null
                : home.resolve(properties.web().consoleStaticDir()).normalize();
        this.sdkRoot = home.resolve(properties.web().webSdkDir()).normalize();
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.HEAD})
    public Mono<ResponseEntity<byte[]>> serve(ServerWebExchange ex) {
        ListenerRegistry.Role role = Requests.role(ex);
        String path = ex.getRequest().getPath().pathWithinApplication().value();
        // Host 已由 GuardFilter 精确校验（登记主机 + 本端口 + 本地到达地址）
        String bridgeOrigin = listeners.scheme().replace("http", "ws") + "://"
                + ex.getRequest().getHeaders().getFirst(org.springframework.http.HttpHeaders.HOST);
        return Mono.fromCallable(() -> role.console() ? console(path) : instance(role.instanceId(), path, bridgeOrigin))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<byte[]> console(String path) throws IOException {
        if (path.startsWith("/__mmab/")) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
        if (consoleRoot == null) {
            return html("<!doctype html><meta charset=\"utf-8\"><title>macmini App 引桥</title>"
                    + "<p>控制台静态文件未配置（mmab.web.console-static-dir）。</p>");
        }
        Optional<Path> file = resolve(consoleRoot, path.equals("/") ? "/index.html" : path);
        if (file.isEmpty() && !lastSegment(path).contains(".")) {
            file = resolve(consoleRoot, "/index.html"); // SPA 路由回落
        }
        return file.map(ContentController::read).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
    }

    private ResponseEntity<byte[]> instance(String instanceId, String path, String bridgeOrigin) throws IOException {
        if (path.startsWith("/__mmab/shim/")) {
            String name = path.substring("/__mmab/shim".length());
            if (!scriptName(name)) {
                throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
            }
            return resolve(sdkRoot, name).map(ContentController::read)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
        }
        InstanceRuntime rt = instances.get(instanceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
        if (path.startsWith("/__mmab/app-shim/")) {
            String name = path.substring("/__mmab/app-shim".length());
            if (!scriptName(name) || name.indexOf('/', 1) >= 0 || rt.adapterRoot() == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
            }
            return resolve(rt.adapterRoot().resolve("shim"), name).map(ContentController::read)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
        }
        if (path.startsWith("/__mmab/")) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
        if (rt.resourceRoot() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
        boolean index = path.equals("/") || path.equals("/index.html");
        Path file = resolve(rt.resourceRoot(), index ? "/index.html" : path)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
        if (!index) {
            return read(file);
        }
        String original = Files.readString(file, StandardCharsets.UTF_8);
        AdapterDescriptor.Web web = rt.descriptor() == null ? null : rt.descriptor().web();
        if (web == null) {
            return html(injectShim(original));
        }
        return html(allowBridgeOrigin(injectScripts(original, web.inject()), bridgeOrigin));
    }

    /** 平台 / 适配器脚本：只提供 .js / .mjs，测试文件除外。 */
    private static boolean scriptName(String name) {
        return (name.endsWith(".mjs") || name.endsWith(".js")) && !name.endsWith(".test.mjs")
                && !name.endsWith(".test.js");
    }

    /** 在第一个 {@code <head>} 之后插入 shim；没有 head 时放在最前。 */
    static String injectShim(String html) {
        return insertAfterHead(html, SHIM_TAG);
    }

    /** M3：按 adapter.yaml {@code web.inject} 顺序插入经典脚本（同步执行，先于原版内联脚本与模块）。 */
    static String injectScripts(String html, List<String> inject) {
        StringBuilder tags = new StringBuilder();
        for (String entry : inject) {
            tags.append("<script src=\"").append(AdapterDescriptor.Web.url(entry)).append("\"></script>");
        }
        return insertAfterHead(html, tags.toString());
    }

    private static String insertAfterHead(String html, String tags) {
        int i = html.toLowerCase(Locale.ROOT).indexOf("<head");
        if (i >= 0) {
            int end = html.indexOf('>', i);
            if (end > 0) {
                return html.substring(0, end + 1) + tags + html.substring(end + 1);
            }
        }
        return tags + html;
    }

    private static final java.util.regex.Pattern META_CSP = java.util.regex.Pattern.compile(
            "(<meta\\s+http-equiv=\"Content-Security-Policy\"\\s+content=\"[^\"]*?connect-src)([^;\"]*)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 原版页面自带 meta CSP 时，只在 connect-src 中追加本实例精确的 WS 来源（{@code wss://<host>:<port>}），
     * 其余指令原样保留，不放宽 script-src 等（设计 §6.3）。没有 meta CSP 或没有 connect-src 时不改。
     */
    static String allowBridgeOrigin(String html, String bridgeOrigin) {
        if (bridgeOrigin == null || !bridgeOrigin.matches("wss?://[A-Za-z0-9.\\[\\]:-]+")) {
            return html;
        }
        java.util.regex.Matcher m = META_CSP.matcher(html);
        if (!m.find() || m.group(2).contains(bridgeOrigin)) {
            return html;
        }
        return html.substring(0, m.end(2)) + " " + bridgeOrigin + html.substring(m.end(2));
    }

    /** 根内安全解析；不存在、目录、越界、经符号链接逃逸都返回空。 */
    static Optional<Path> resolve(Path root, String requestPath) {
        if (requestPath == null || !requestPath.startsWith("/") || requestPath.contains("\\")
                || requestPath.contains("\0") || requestPath.contains("%")) {
            return Optional.empty();
        }
        for (String seg : requestPath.substring(1).split("/", -1)) {
            if (seg.equals("..") || seg.equals(".") || (seg.isEmpty() && !requestPath.equals("/"))) {
                return Optional.empty();
            }
        }
        try {
            Path realRoot = root.toRealPath();
            Path candidate = root.resolve(requestPath.substring(1)).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path real = candidate.toRealPath();
            return real.startsWith(realRoot) ? Optional.of(real) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static ResponseEntity<byte[]> read(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
            }
            String name = file.getFileName().toString();
            if (name.endsWith(".html")) {
                return ResponseEntity.ok().contentType(contentType(name)).cacheControl(CacheControl.noStore())
                        .body(Files.readAllBytes(file));
            }
            // no-cache + 弱 ETag（大小 + 修改时间）：浏览器每次重新验证，未变化时 304，不必重传几十 MB 的包
            java.nio.file.attribute.BasicFileAttributes a =
                    Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            String etag = "W/\"" + Long.toHexString(a.size()) + "-" + Long.toHexString(a.lastModifiedTime().toMillis()) + "\"";
            return ResponseEntity.ok().contentType(contentType(name)).cacheControl(CacheControl.noCache())
                    .eTag(etag).lastModified(a.lastModifiedTime().toMillis())
                    .body(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
    }

    private static ResponseEntity<byte[]> html(String html) {
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).body(html.getBytes(StandardCharsets.UTF_8));
    }

    private static MediaType contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) {
            return new MediaType("text", "html", StandardCharsets.UTF_8);
        }
        if (n.endsWith(".js") || n.endsWith(".mjs")) {
            return new MediaType("text", "javascript", StandardCharsets.UTF_8);
        }
        if (n.endsWith(".css")) {
            return new MediaType("text", "css", StandardCharsets.UTF_8);
        }
        if (n.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (n.endsWith(".svg")) {
            return MediaType.valueOf("image/svg+xml");
        }
        if (n.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (n.endsWith(".ico")) {
            return MediaType.valueOf("image/x-icon");
        }
        if (n.endsWith(".woff2")) {
            return MediaType.valueOf("font/woff2");
        }
        String extra = EXTRA_TYPES.get(n.substring(n.lastIndexOf('.') + 1));
        if (extra != null) {
            return MediaType.valueOf(extra);
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
