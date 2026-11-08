package io.github.dipinllx.macminiappbridge.web;

import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.protocol.AdapterProtocol;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

/** 控制台监听（Spring Boot 主服务器）的地址 / TLS，以及数据面 WS 的帧上限。 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    /**
     * 控制台与实例监听共用 {@code mmab.web.tls-bundle}；未配置 TLS 时只允许回环地址（开发 / 测试），
     * 否则拒绝启动（设计 §6.3：TLS 默认必需）。Spring 主服务器只绑主地址（列表第一个），
     * 其余地址由 {@link InstanceListeners} 以同一端口补绑。
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableWebServerFactory> mmabConsoleServer(MmabProperties properties) {
        return factory -> {
            InetAddress bind;
            try {
                bind = InetAddress.getByName(properties.console().bindAddress());
            } catch (UnknownHostException e) {
                throw new IllegalStateException("mmab.console.bind-address is not resolvable", e);
            }
            String bundle = properties.web().tlsBundle();
            if (bundle == null && !properties.console().allLoopback()) {
                throw new IllegalStateException("plain HTTP is only allowed on loopback; configure mmab.web.tls-bundle");
            }
            factory.setAddress(bind);
            factory.setPort(properties.console().port());
            if (bundle != null) {
                factory.setSsl(Ssl.forBundle(bundle));
            }
        };
    }

    /** WS 单帧上限 1 MiB（设计 §9.3），超限由 Netty 以 1009 关闭。 */
    @Bean
    HandshakeWebSocketService mmabWebSocketService() {
        return new HandshakeWebSocketService(new ReactorNettyRequestUpgradeStrategy(
                () -> WebsocketServerSpec.builder().maxFramePayloadLength(AdapterProtocol.MAX_FRAME_BYTES)));
    }
}
