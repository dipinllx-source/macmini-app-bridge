package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 控制台头像：本机已安装应用自身的图标（adapter.yaml 的 {@code app.icon}，位于第一个 {@code executableAllowlist}
 * 所在 .app 包内），运行时用 macOS {@code sips} 转为 256 px PNG（高分屏下 64 px 显示仍清晰） 缓存在 {@code <mmab.data-dir>/icons/}。
 * 图标属于各应用，永不入库；路径严格限定在 {@code /Applications/*.app/Contents/Resources/*.icns}。
 */
@Component
public class AppIcons {

    private static final Logger log = LoggerFactory.getLogger(AppIcons.class);
    private static final Pattern EXECUTABLE = Pattern.compile("^(/Applications/[A-Za-z0-9 ._-]{1,64}\\.app)/Contents/MacOS/[A-Za-z0-9 ._-]{1,64}$");
    private static final Pattern ICON = Pattern.compile("^Contents/Resources/[A-Za-z0-9._-]{1,64}\\.icns$");
    static final int MAX_PNG_BYTES = 512 * 1024;

    private final MmabProperties properties;
    private final Path cacheDir;
    private final Map<String, Optional<Path>> sources = new ConcurrentHashMap<>();

    public AppIcons(MmabProperties properties) {
        this.properties = properties;
        this.cacheDir = Path.of(properties.dataDir()).resolve("icons");
    }

    /** @return 应用图标 PNG；没有声明 / 不存在 / 转换失败时为空（控制台用内置图标）。 */
    public Optional<byte[]> png(String appId) {
        Optional<Path> icns = sources.computeIfAbsent(appId, this::locate);
        if (icns.isEmpty()) {
            return Optional.empty();
        }
        try {
            Path src = icns.get();
            long stamp = Files.getLastModifiedTime(src).toMillis();
            Path out = cacheDir.resolve(appId + "-256-" + Long.toHexString(stamp) + ".png");
            if (!Files.isRegularFile(out)) {
                Files.createDirectories(cacheDir);
                Path tmp = cacheDir.resolve(appId + ".tmp.png");
                Process p = new ProcessBuilder("/usr/bin/sips", "-s", "format", "png", "-Z", "256",
                        src.toString(), "--out", tmp.toString()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || !Files.isRegularFile(tmp)) {
                    p.destroyForcibly();
                    return Optional.empty();
                }
                Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(out) > MAX_PNG_BYTES) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(out));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    Optional<Path> locate(String appId) {
        try {
            MmabProperties.AdapterPackage pkg = properties.adapters().stream()
                    .filter(a -> a.appId().equals(appId)).findFirst().orElse(null);
            if (pkg == null || pkg.descriptor() == null) {
                return Optional.empty();
            }
            Path yaml = Path.of(properties.home()).resolve(pkg.descriptor()).normalize();
            Object root = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(Files.readString(yaml, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> m) || !(m.get("app") instanceof Map<?, ?> app)) {
                return Optional.empty();
            }
            return resolve(app.get("executableAllowlist") instanceof List<?> l && !l.isEmpty() ? String.valueOf(l.get(0)) : null,
                    app.get("icon") == null ? null : String.valueOf(app.get("icon")));
        } catch (IOException | RuntimeException e) {
            log.debug("no icon for {}: {}", appId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 由可执行文件路径与包内图标路径得到 icns；任何不符合白名单或越出包目录的情况返回空。 */
    static Optional<Path> resolve(String executable, String icon) throws IOException {
        if (executable == null || icon == null) {
            return Optional.empty();
        }
        var exe = EXECUTABLE.matcher(executable);
        if (!exe.matches() || !ICON.matcher(icon).matches()) {
            return Optional.empty();
        }
        Path bundle = Path.of(exe.group(1));
        Path file = bundle.resolve(icon).normalize();
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(bundle.toRealPath())) {
            return Optional.empty();
        }
        return Optional.of(file);
    }
}
