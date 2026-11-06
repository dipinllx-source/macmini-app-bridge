package io.github.dipinllx.minimacappbridge;

import io.github.dipinllx.minimacappbridge.config.MmabProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * MiniMacAppBridge（miniMac App 引桥）Java 核心入口。
 *
 * <p>M1 离线协议与安全骨架：多端口 TLS 监听、开窗票据、会话、租约、网关过滤器、WS 数据面与适配器进程管理；
 * 只接入离线 mock 适配器，未接入任何真实应用，不可用于远程访问。
 */
@SpringBootApplication
@EnableConfigurationProperties(MmabProperties.class)
public class MiniMacAppBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniMacAppBridgeApplication.class, args);
    }
}
