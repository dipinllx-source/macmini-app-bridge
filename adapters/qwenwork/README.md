# adapters/qwenwork

千问办公（QwenWork）参考适配器，策略族 **S1 electron-main-inject**（主进程注入）。M3。

```text
浏览器（原版 renderer + 注入脚本）            mmab-server                 qwenwork driver            千问主进程
 codec.js / transport.js / electron-compat.js
 shim/qwen-electron.js  ──WS / 同步 call──▶  网关（会话、租约、能力闸门）──Adapter Protocol v1──▶ driver/main.mjs
 mmab-preload.js（原版 preload 包装）                                     ──mmab-qwen-link-v1──▶ payload/main-bridge.js
                                                                           （127.0.0.1，双向 HMAC）  ipcMain / 主窗口 webContents
```

| 目录 | 说明 |
|---|---|
| `adapter.yaml` | 描述文件：操作白名单（`qwenwork.bootstrap / invoke / send / sendSync`，事件 `qwenwork.ipc`）、注入顺序、证据层级 |
| `payload/main-bridge.js` | 注入主进程的桥：只监听 127.0.0.1 临时端口、只接受一个经双向 HMAC 认证的 driver 连接；以主窗口身份调用 IPC；镜像主进程推送；拒绝作用于本机窗口 / 托盘 / 访达的通道；tRPC id 按连接重映射（3e9 起，与应用自身和旧原型桥错开） |
| `driver/` | 适配器进程：每次（重新）连接前核验交接文件（PID + 启动时间 + 可执行文件 + 端口归属）；payload 原样以 mmab-sv1 文本转发，不重新编码 |
| `shim/qwen-electron.js` | 浏览器侧：为原版 preload 提供 `require('electron')` 与 `process`；剪贴板写入与外链（仅 http/https）在浏览器本机执行；附件暂不支持 |
| `tools/extract.mjs` | 从本机 App 包提取原版 renderer 到 `mmab.data-dir`，并生成 preload 包装 `mmab-preload.js`（不入库） |
| `tools/inject.mjs` | **操作者显式步骤**：注入 payload、写 0600 交接文件、关闭 inspector |
| `tests/` | 离线契约测试（假的 Electron 主进程），`node --test adapters/**/*.test.mjs` |

## 安全边界

- driver 从不打开应用的 inspector；注入只由操作者运行 `tools/inject.mjs` 触发。
- `inject.mjs` 只接受**以精确参数 `--inspect=127.0.0.1:9229` 启动**的唯一主进程；普通启动的应用一律拒绝（`WAIT_CONFIRM`），从不重启、退出或附加。
- inspector 已关闭时，只有在提供“信号证明”（Electron Framework sha256 与本机安装一致的记录）时才发送 SIGUSR1 重新打开；未证明的构建上 SIGUSR1 可能直接终止应用。
- 注入完成或失败都会关闭 inspector 并复核端口已关；关不掉时报 `INSPECTOR_CLEANUP_FAILED`，交人工处理，不杀应用。
- 交接文件（端口 + 每次注入新生成的 32 字节 link key）只存 `mmab.data-dir/qwenwork/link.json`，0600 / 目录 0700；driver 发现权限不对即拒绝。
- 与本机窗口共享同一账号与状态；IPC 只能粗粒度归入 `ui.render`，**不是只读沙箱**。
- 当前限制：driver 使用默认数据目录（`~/Library/Application Support/MacminiAppBridge`）；平台自定义 `mmab.data-dir` 时 qwenwork 交接文件路径尚不跟随。

## 实机切换步骤（需操作者批准）

1. 提取资源（只读 App 包）：`node adapters/qwenwork/tools/extract.mjs`
2. 预检（不发信号、不连接）：`node adapters/qwenwork/tools/inject.mjs --dry-run [--reopen-inspector --signal-proof <file>]`
3. 注入：`node adapters/qwenwork/tools/inject.mjs [--reopen-inspector --signal-proof <file>]`
   payload 与已有的其他进程内桥共存（独立全局名、独立 tRPC id 段），不中断它们。
4. 平台配置 `qwen-01`：`enabled: true`（`ui.render` / `ui.history` 已有 live 证据，无需 `verify-capabilities`）。
5. 回滚：`node adapters/qwenwork/tools/inject.mjs --stop [--reopen-inspector --signal-proof <file>]` 停止 payload、删除交接文件；或直接重启应用（payload 不持久化）。
