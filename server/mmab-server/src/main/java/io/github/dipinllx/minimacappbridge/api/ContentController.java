package io.github.dipinllx.minimacappbridge.api;

import io.github.dipinllx.minimacappbridge.config.MmabProperties;
import io.github.dipinllx.minimacappbridge.instances.InstanceRegistry;
import io.github.dipinllx.minimacappbridge.instances.InstanceRuntime;
import io.github.dipinllx.minimacappbridge.protocol.ErrorCode;
import io.github.dipinllx.minimacappbridge.web.ApiException;
import io.github.dipinllx.minimacappbridge.web.ListenerRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    private final InstanceRegistry instances;
    private final Path home;
    private final Path consoleRoot;
    private final Path sdkRoot;

    public ContentController(InstanceRegistry instances, MmabProperties properties) {
        this.instances = instances;
        this.home = Path.of(properties.home()).toAbsolutePath().normalize();
        this.consoleRoot = properties.web().consoleStaticDir() == null ? null
                : home.resolve(properties.web().consoleStaticDir()).normalize();
        this.sdkRoot = home.resolve(properties.web().webSdkDir()).normalize();
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.HEAD})
    public Mono<ResponseEntity<byte[]>> serve(ServerWebExchange ex) {
        ListenerRegistry.Role role = Requests.role(ex);
        String path = ex.getRequest().getPath().pathWithinApplication().value();
        return Mono.fromCallable(() -> role.console() ? console(path) : instance(role.instanceId(), path))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<byte[]> console(String path) throws IOException {
        if (path.startsWith("/__mmab/")) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
        if (consoleRoot == null) {
            return html("<!doctype html><meta charset=\"utf-8\"><title>miniMac App 引桥</title>"
                    + "<p>控制台静态文件未配置（mmab.web.console-static-dir）。</p>");
        }
        Optional<Path> file = resolve(consoleRoot, path.equals("/") ? "/index.html" : path);
        if (file.isEmpty() && !lastSegment(path).contains(".")) {
            file = resolve(consoleRoot, "/index.html"); // SPA 路由回落
        }
        return file.map(this::read).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
    }

    private ResponseEntity<byte[]> instance(String instanceId, String path) throws IOException {
        if (path.startsWith("/__mmab/shim/")) {
            String name = path.substring("/__mmab/shim".length());
            if (!name.endsWith(".mjs") || name.endsWith(".test.mjs")) {
                throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
            }
            return resolve(sdkRoot, name).map(this::read)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
        }
        if (path.startsWith("/__mmab/")) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
        InstanceRuntime rt = instances.get(instanceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
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
        return html(injectShim(original));
    }

    /** 在第一个 {@code <head>} 之后插入 shim；没有 head 时放在最前。 */
    static String injectShim(String html) {
        int i = html.toLowerCase(Locale.ROOT).indexOf("<head");
        if (i >= 0) {
            int end = html.indexOf('>', i);
            if (end > 0) {
                return html.substring(0, end + 1) + SHIM_TAG + html.substring(end + 1);
            }
        }
        return SHIM_TAG + html;
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

    private ResponseEntity<byte[]> read(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
            }
            byte[] body = Files.readAllBytes(file);
            String name = file.getFileName().toString();
            return ResponseEntity.ok().contentType(contentType(name))
                    .cacheControl(name.endsWith(".html") ? CacheControl.noStore() : CacheControl.noCache())
                    .body(body);
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
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
