# ADR-0002：Java 21 + Spring Boot 4.1 + Vue 3 技术栈

- 状态：已接受（2026-11-07）

## 决定

- JDK 21 LTS（Temurin），Spring Boot 4.1.x GA（当前锁定 4.1.2，在 Maven Central 核对为最新 4.1.x GA；不使用 SNAPSHOT / RC / M）。
- WebFlux + Reactor Netty 统一承担 REST、静态资源、WS 代理与多端口监听；阻塞操作（进程查询、hash、SQLite）放入有界专用执行器。
- Spring Security WebFlux + 服务端随机 Session；不使用长期浏览器 JWT。
- 单机持久化 SQLite（Spring JDBC + 版本化迁移）；出现多控制节点时迁 PostgreSQL。
- 控制台：Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus，只做管理界面，不重写应用界面。
- 不引入 Redis、Kafka、Kubernetes 或微服务拆分。

## 后果

- 升级 Spring Boot 前需重新核对官方支持矩阵与三方驱动兼容，并更新本 ADR。
- Java 与 SQLite 驱动在 macOS arm64 上的兼容性需在 M1 契约测试中验证。
