/**
 * 数据面代理：原版资源与 WS 中继、精确 Origin / Cookie 名校验、有界缓冲与 backpressure（设计 §5.2、§9.3）。不是开放代理。
 *
 * <p>M1：实例端口 WS 数据面、有界队列、慢客户端隔离；原版资源反向代理（S3 等）尚未实现。
 */
package io.github.dipinllx.macminiappbridge.proxy;
