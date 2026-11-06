/**
 * MMAB Adapter Protocol v1（设计文档 §9.2、§9.4、§10.2、§10.3、§14.4）。
 *
 * <p>本包定义 Java 核心与适配器进程之间的线上契约：版本化 envelope、结构化错误码、
 * 能力四态与策略族标识；子包 {@code codec} 是平台结构化值 codec（mmab-sv1），
 * {@code wire} 是平台 ↔ 适配器内部认证与帧格式，{@code ports} 是 MessagePort 生命周期表。
 * Java 侧<strong>不</strong>解释任何应用的业务 payload，只校验 envelope、预算与 codec 结构，
 * 不把 payload 反序列化为任意 Java 类。
 *
 * <p>状态：M1 离线实现（只与 mock adapter 联调）。语言无关的 JSON Schema 与规范见仓库
 * {@code sdk/protocol/}。
 */
package io.github.dipinllx.minimacappbridge.protocol;
