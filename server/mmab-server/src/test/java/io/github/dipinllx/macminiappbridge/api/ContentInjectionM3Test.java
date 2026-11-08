package io.github.dipinllx.macminiappbridge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** M3：按 adapter.yaml web.inject 注入经典脚本；meta CSP 只追加本实例精确 WS 来源。 */
class ContentInjectionM3Test {

    @Test
    void classicScriptsAreInjectedInOrderRightAfterHead() {
        String html = "<!doctype html><html><head><meta charset=utf-8><script>inline()</script>"
                + "<script type=\"module\" src=\"./assets/index.js\"></script></head></html>";
        String out = ContentController.injectScripts(html,
                List.of("platform:codec.js", "adapter:shim.js", "resource:/mmab-preload.js"));
        assertThat(out).startsWith("<!doctype html><html><head><script src=\"/__mmab/shim/codec.js\"></script>"
                + "<script src=\"/__mmab/app-shim/shim.js\"></script><script src=\"/mmab-preload.js\"></script>"
                + "<meta charset=utf-8><script>inline()</script>");
        assertThat(out).doesNotContain("type=\"module\" src=\"/__mmab");
    }

    @Test
    void cspGetsOnlyTheExactBridgeOriginAppended() {
        String csp = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "script-src 'self'; connect-src 'self' https://api.example; img-src 'self'\">";
        String out = ContentController.allowBridgeOrigin(csp, "wss://10.0.0.5:8445");
        assertThat(out).contains("connect-src 'self' https://api.example wss://10.0.0.5:8445; img-src 'self'")
                .contains("script-src 'self';").contains("default-src 'none';");
        assertThat(ContentController.allowBridgeOrigin(out, "wss://10.0.0.5:8445")).as("idempotent").isEqualTo(out);
        assertThat(ContentController.allowBridgeOrigin("<p>no csp</p>", "wss://10.0.0.5:8445"))
                .isEqualTo("<p>no csp</p>");
        assertThat(ContentController.allowBridgeOrigin(csp, "wss://evil\"onload=x")).as("bad origin ignored")
                .isEqualTo(csp);
        String noConnect = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'self'\">";
        assertThat(ContentController.allowBridgeOrigin(noConnect, "wss://10.0.0.5:8445")).isEqualTo(noConnect);
    }
}
