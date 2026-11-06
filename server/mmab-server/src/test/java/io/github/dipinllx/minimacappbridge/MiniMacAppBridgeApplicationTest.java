package io.github.dipinllx.minimacappbridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.minimacappbridge.config.MmabProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
class MiniMacAppBridgeApplicationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MmabProperties properties;

    @Test
    void bindsSuggestedPortLayout() {
        assertThat(properties.console().port()).isEqualTo(8443);
        assertThat(properties.console().bindAddress()).isEqualTo("127.0.0.1");
        assertThat(properties.instances())
                .extracting(MmabProperties.Instance::id, MmabProperties.Instance::port, MmabProperties.Instance::enabled)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("qwen-01", 8444, false),
                        org.assertj.core.groups.Tuple.tuple("codex-01", 8445, false));
    }

    @Test
    void healthEndpointReportsDesignSkeleton() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        client.get().uri("/__mmab/api/v1/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.platform").isEqualTo("MiniMacAppBridge")
                .jsonPath("$.shortName").isEqualTo("mmab")
                .jsonPath("$.status").isEqualTo("design-skeleton")
                .jsonPath("$.version").isEqualTo("0.1.0-SNAPSHOT")
                .jsonPath("$.adapterProtocol").isEqualTo("1.0-draft")
                .jsonPath("$.registeredInstances").isEqualTo(2);
    }
}
