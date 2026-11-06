/**
 * 独占租约：每实例一个 ACTIVE lease、heartbeat / expiry、generation 与 fencing（设计 §9.1）。
 *
 * <p>M1：内存实现（{@link LeaseManager}），进程重启即全部失效。
 */
package io.github.dipinllx.minimacappbridge.leases;
