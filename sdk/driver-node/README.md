# sdk/driver-node

MMAB Adapter SDK（Node，零依赖）——适配器进程一侧的协议实现（M1）。

| 文件 | 说明 |
|---|---|
| `adapter-runtime.mjs` | `runAdapter({adapterVersion, handlers})`：读 stdin 引导、只监听 127.0.0.1、握手认证、单一平台连接、fence、操作分发、事件推送（尊重 socket 背压） |
| `wire.mjs` | HMAC 握手 / 帧 MAC / 严格计数 / 有界分行，规范见 `../protocol/adapter-wire-v1.md` |
| `envelope.mjs` | 与 `envelope.schema.json` 对齐的最小校验 |
| `wire.test.mjs` | 跨语言固定向量、重放 / 反射 / 篡改、envelope fixture |

handler 签名：`(value, ctx) => result | Promise<result>`；`value` 已按 mmab-sv1 解码；`ctx.emit(op, value)` 推送事件，
`ctx.stillCurrent()` 判断租约是否仍有效。handler 抛出 `CodecError` / 带 `code` 的错误时回对应错误码。

规划（M3）：进程身份核验（PID + 启动时间 + 可执行文件 + profile + 端口所有者）、discover / observe / probe 固定操作。
