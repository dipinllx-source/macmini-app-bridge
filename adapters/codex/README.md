# adapters/codex

Codex 参考适配器，策略族 **S2 electron-renderer-cdp**（页面进程调试接口 + MessagePort 中继）。M3。

```text
浏览器（原版 webview + 注入脚本）              mmab-server                  codex driver             专用 Codex 实例
 codec.js / transport.js / ports.js
 shim/codex-bridge.js（electronBridge）──WS──▶ 网关（会话、租约、能力闸门、──Adapter Protocol v1──▶ driver/main.mjs
   app-host MessageChannel ⇄ 端口中继          端口表 w/n）                    ──CDP（127.0.0.1）──▶ payload/renderer-bridge.js
                                                                                                     electronBridge / app host
```

| 目录 | 说明 |
|---|---|
| `adapter.yaml` | 操作白名单（`codex.bootstrap / read / viewReady / viewRead / connectAppHost / connectGit`，事件 `codex.hostMessage / theme / gitPort`）、注入顺序、证据层级 |
| `payload/renderer-bridge.js` | 经 CDP 装入专用实例的页面进程：每个浏览器连接一个端口中继（`n` id 空间）；只放行 `ready` 与 3 个启动读取；宿主消息镜像时排除需本机视图应答的 `*-request`、内部 `codex_desktop:*`、分片消息和带端口的消息；Git 端口只交给发起请求的连接；浏览器帧按到达顺序分批执行 |
| `driver/` | 每次附加前核验专用实例（精确可执行文件、精确 `--user-data-dir`、调试端口只绑 127.0.0.1）与资源版本；页面进程重载后重新装入并关闭旧会话 |
| `shim/codex-bridge.js` | 浏览器侧 `window.electronBridge`；Sentry 本地空实现，不外发 |
| `tools/extract.mjs` | 从本机 App 包提取原版 webview 到 `mmab.data-dir/codex/<版本>/webview`（不入库） |
| `tools/launch.mjs` | 启动 / 停止**专用实例**（默认 profile `mmab.data-dir/codex/profile`，调试端口 9341）；只对核验过的专用 PID 发 SIGTERM |
| `tests/` | 离线契约测试（同一 realm 内的假页面进程、真实 MessageChannel） |

## 安全边界

- 只附加专用实例；操作者的正常 Codex（无调试参数）永远不匹配。
- 调试端口在专用实例运行期间常开（127.0.0.1）：本机同一用户的其他进程可操控该窗口——启用即接受此风险。
- **独立 profile 不隔离账号**：2026-11-09 实测，全新 profile 启动后直接是已登录状态（系统级凭据共享）。
- app-host 通道承载原版全部业务 RPC，只能粗粒度归入 `ui.render`，不是只读沙箱；Git（`app.git`）单独闸门，未验收前关闭。

## 使用

```bash
node adapters/codex/tools/extract.mjs                 # Codex 升级后需重新提取（驱动发现版本不符会拒绝：BLOCKED_UPGRADE）
node adapters/codex/tools/launch.mjs                  # 启动专用实例
# 平台：adapters[codex].command 追加 --profile <profile> --cdp-port 9341 --assets-dir <提取目录>；codex-01 enabled: true
node adapters/codex/tools/launch.mjs --stop           # 停止专用实例
```
