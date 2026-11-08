/**
 * 身份与会话：操作者身份、控制台 / 实例会话分离、开窗一次性票据（设计 §5.3、§6）。不存明文 session 或 token。
 *
 * <p>M1：操作者口令（PBKDF2）、控制台 / 实例会话、一次性开窗票据已实现，均只在内存。
 */
package io.github.dipinllx.macminiappbridge.identity;
