/**
 * 持久化：单机 SQLite（WAL、短事务、阻塞调用移出 event loop），只存低频元数据（设计 §3、§10.1）。
 *
 * <p>占位包：M2 引入 SQLite。
 */
package io.github.dipinllx.minimacappbridge.persistence;
