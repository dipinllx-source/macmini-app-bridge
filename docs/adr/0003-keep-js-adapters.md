# ADR-0003：保留 JS 适配器层，Java 不直接替代

- 状态：已接受（2026-11-07）

## 背景

两类参考桥的关键对象只存在于 JavaScript 运行时：

- 主进程注入（S1）需要访问 Electron 主进程的 `ipcMain`、`webContents`；
- renderer CDP（S2）需要在 renderer 中调用 preload 暴露的桥对象，并创建、转移 `MessagePort`。

## 决定

- 平台核心用 Java：鉴权、会话、租约、生命周期、代理、审计、能力门禁。
- 适配器以**独立进程**运行（首版 Node.js），通过 MMAB Adapter Protocol 与 Java 通信；注入应用内的部分保持为 JS。
- 不在 JVM 内加载第三方适配器代码；平台不公开任意 eval、任意 CDP 方法或任意 IPC 通道。
- 首先封装已验证的原型行为，逐步把通用职责迁入 Java，不做一次性重写。

## 后果

- 仓库包含 Java、Vue、Node 三种运行时的构建。
- 适配器需要与平台进行内部身份认证，loopback 不等于可信。
