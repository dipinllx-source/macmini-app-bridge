/**
 * 生命周期：desired-state / actual-state / reconcile、每实例串行 owner lock、BLOCKED / WAIT_CONFIRM、单一恢复所有权（设计 §7）。
 *
 * <p>占位包：完整 desired/actual-state 对账在 M2 起实现；M1 只有启动拉起与手动重建。
 */
package io.github.dipinllx.macminiappbridge.lifecycle;
