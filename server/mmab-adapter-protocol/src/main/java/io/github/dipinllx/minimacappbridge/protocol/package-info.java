/**
 * MMAB Adapter Protocol v1 草案（设计文档 §9.2、§10.2、§10.3、§14.4）。
 *
 * <p>本包只定义 Java 核心与适配器进程之间的线上契约类型：版本化 envelope、
 * 结构化错误码、能力四态与策略族标识。它<strong>不</strong>解释任何应用的业务
 * payload —— payload 由 profile 专用 codec 处理，Java 侧只校验 envelope 与预算，
 * 不把 payload 反序列化为任意 Java 类。
 *
 * <p>状态：draft。字段与枚举值可能在 M1 契约测试中调整；语言无关的 JSON Schema
 * 见仓库 {@code sdk/protocol/schemas/}。
 */
package io.github.dipinllx.minimacappbridge.protocol;
