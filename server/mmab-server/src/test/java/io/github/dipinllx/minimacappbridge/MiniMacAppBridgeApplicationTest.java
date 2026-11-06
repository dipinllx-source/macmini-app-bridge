package io.github.dipinllx.minimacappbridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.minimacappbridge.config.MmabProperties;
import io.github.dipinllx.minimacappbridge.instances.InstanceRegistry;
import io.github.dipinllx.minimacappbridge.instances.InstanceRuntime;
import io.github.dipinllx.minimacappbridge.web.ListenerRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 默认配置：三个实例都只登记不启用，不启动任何适配器、不开实例监听。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "mmab.console.port=0")
class MiniMacAppBridgeApplicationTest {

    @Autowired
    MmabProperties properties;

    @Autowired
    ListenerRegistry listeners;

    @Autowired
    InstanceRegistry instances;

    @Test
    void bindsSuggestedPortLayoutWithEverythingDisabled() {
        assertThat(properties.console().bindAddress()).isEqualTo("127.0.0.1");
        assertThat(properties.instances())
                .extracting(MmabProperties.Instance::id, MmabProperties.Instance::port, MmabProperties.Instance::enabled)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("qwen-01", 8444, false),
                        org.assertj.core.groups.Tuple.tuple("codex-01", 8445, false),
                        org.assertj.core.groups.Tuple.tuple("mock-01", 8446, false));
        assertThat(properties.testing().allowTestAdapters()).isFalse();
        assertThat(instances.all()).allMatch(rt -> rt.state() == InstanceRuntime.State.DISABLED);
        assertThat(listeners.registeredInstancePorts()).isEmpty();
    }

    @Test
    void healthEndpointReportsM1Offline() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + listeners.consolePort() + "/__mmab/api/v1/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"platform\":\"MiniMacAppBridge\"", "\"shortName\":\"mmab\"",
                "\"status\":\"m1-offline\"", "\"version\":\"0.1.0-SNAPSHOT\"", "\"adapterProtocol\":\"1.0-draft\"",
                "\"registeredInstances\":3");
        assertThat(r.headers().firstValue("Cross-Origin-Resource-Policy")).contains("same-origin");
        assertThat(r.headers().firstValue("Referrer-Policy")).contains("same-origin");
    }
}
