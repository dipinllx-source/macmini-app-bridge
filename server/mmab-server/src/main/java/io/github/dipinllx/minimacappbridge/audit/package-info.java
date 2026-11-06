/**
 * 审计：只记固定元数据，不记正文、文件内容、账号标识、cookie、票据、口令或 CDP 表达式（设计 §6.3）。
 *
 * <p>M1：内存环形缓冲 + 日志行（{@link AuditLog}）；持久化在 M2。
 */
package io.github.dipinllx.minimacappbridge.audit;
