package io.github.dipinllx.macminiappbridge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 资源根限制与 shim 注入（设计 §6.3：路径根限制、符号链接检查）。 */
class ContentControllerTest {

    @TempDir
    Path tmp;

    @Test
    void resolveStaysInsideTheRoot() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.writeString(root.resolve("index.html"), "<html></html>");
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/b.js"), "x");
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "s");
        Files.createSymbolicLink(root.resolve("link.txt"), secret);

        assertThat(ContentController.resolve(root, "/index.html")).isPresent();
        assertThat(ContentController.resolve(root, "/a/b.js")).isPresent();
        assertThat(ContentController.resolve(root, "/../secret.txt")).isEmpty();
        assertThat(ContentController.resolve(root, "/a/../../secret.txt")).isEmpty();
        assertThat(ContentController.resolve(root, "/%2e%2e/secret.txt")).isEmpty();
        assertThat(ContentController.resolve(root, "/a\\b.js")).isEmpty();
        assertThat(ContentController.resolve(root, "/link.txt")).as("symlink escaping the root").isEmpty();
        assertThat(ContentController.resolve(root, "/a")).as("directories are not served").isEmpty();
        assertThat(ContentController.resolve(root, "/a//b.js")).isEmpty();
        assertThat(ContentController.resolve(root, "relative")).isEmpty();
    }

    @Test
    void shimIsInjectedRightAfterHead() {
        assertThat(ContentController.injectShim("<!doctype html><html><HEAD lang=x><title>t</title></head></html>"))
                .isEqualTo("<!doctype html><html><HEAD lang=x>" + ContentController.SHIM_TAG
                        + "<title>t</title></head></html>");
        assertThat(ContentController.injectShim("<p>no head</p>")).startsWith(ContentController.SHIM_TAG);
    }

    @Test
    void largeAssetsAreServedWithValidatorsAndHtmlIsNeverCached() throws Exception {
        // 千问 2.0.0 的 vendor 包约 37 MiB：旧的 32 MiB 上限让它 404，整页白屏（M3 实机发现）
        Path big = tmp.resolve("vendor.js");
        try (var ch = java.nio.channels.FileChannel.open(big, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(new byte[] {1}), 40L * 1024 * 1024 - 1);
        }
        var r = ContentController.read(big);
        assertThat(r.getBody()).hasSize(40 * 1024 * 1024);
        assertThat(r.getHeaders().getETag()).startsWith("W/\"");
        assertThat(r.getHeaders().getLastModified()).isPositive();
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-cache");

        Path html = Files.writeString(tmp.resolve("index.html"), "<html></html>");
        var h = ContentController.read(html);
        assertThat(h.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(h.getHeaders().getETag()).isNull();
    }
}
