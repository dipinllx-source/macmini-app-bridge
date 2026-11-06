# Adapter Protocol v1：平台 ↔ 适配器内部认证与帧格式

实现：Java `protocol.wire.WireAuth` + `instances.ProcessAdapterClient`（平台侧）；
Node `sdk/driver-node/wire.mjs` + `adapter-runtime.mjs`（适配器侧）。设计依据 §6.2。

## 1. 启动

1. 平台按**静态配置**的命令启动适配器进程（命令不来自任何 API 参数），工作目录为 `mmab.home`，
   并从子进程环境中去掉 `MMAB_*` 变量。
2. 平台向 stdin 写一行后立即关闭 stdin：

   ```json
   {"mmabAdapterBootstrap":1,"serviceKey":"<32 字节 base64>","instanceId":"mock-01","generation":1,"profileId":"mock/0.1.0","protocol":"1.0"}
   ```

   服务密钥每次启动随机生成，不进 argv、环境变量、文件或日志。
3. 适配器只监听 `127.0.0.1` 的随机端口，在 stdout 打印 `{"mmabAdapterReady":1,"port":N,"adapterVersion":"..."}`。

## 2. 握手（每个连接，3 秒内完成，否则断开）

```text
adapter → {"t":"hello","protocol":{"min":"1.0","max":"1.0"},"nonce":A,"instanceId":"mock-01","adapterVersion":"0.1.0"}
platform → {"t":"auth","protocol":"1.0","nonce":C,"mac":HMAC(key,"mmab-auth-v1\nplatform\nA\nC\n<instanceId>\n<generation>\n1.0")}
adapter → {"t":"auth-ok","mac":HMAC(key,"mmab-auth-v1\nadapter\nA\nC\n<instanceId>\n<generation>\n1.0")}
```

- 双向认证：平台也校验 `auth-ok` 的 MAC，防止连到冒充的监听者。
- 任何失败：适配器回 `{"t":"error","code":"UNAUTHENTICATED"}`（或 `PROTOCOL_VERSION_UNSUPPORTED`）并关闭；
  不回显原因细节。认证前单行上限 4 KiB。
- 同一时刻只接受一个已认证的平台连接；该连接关闭后适配器进程退出（不留孤儿进程）。
- `sessionKey = HMAC(key, "mmab-session-v1\nA\nC")`；A、C 每个连接都新生成，旧连接的握手无法重放到新连接。

## 3. 帧

```text
<64 位小写 hex HMAC(sessionKey, 方向 + "\n" + json)> <空格> json
json = {"n": <从 1 开始严格 +1>, "env": <envelope>}
方向：平台→适配器 "p2a"，适配器→平台 "a2p"
```

MAC 不符、`n` 不连续 → `REPLAY_REJECTED`，断开连接。单行上限 1 MiB + 64 KiB。

## 4. fence 与适配器侧拒绝

平台在租约 / generation 变化时发送 `CONTROL`、`operation: "mmab.fence"`、`connectionId: "platform"`，
envelope 的 `processGeneration` / `leaseId` / `fencingToken` 即新的 fence（无租约时 `leaseId: "none"`）。
fence 只能前进。适配器对每个 REQUEST 依次检查：

| 条件 | 回复 |
|---|---|
| generation ≠ 当前 | `STALE_GENERATION` |
| 当前无租约 | `LEASE_REQUIRED` |
| leaseId / fencingToken ≠ 当前 | `STALE_GENERATION` |
| deadline 已过 | `DEADLINE_EXCEEDED` |
| codecVersion 不支持 | `CODEC_UNSUPPORTED` |
| 适配器没有实现该操作 | `UNKNOWN_OPERATION` |

平台侧同样丢弃旧 generation / 已撤销租约的迟到帧（执行状态未知，不伪装成成功）。

固定测试向量：`fixtures/wire-v1-vector.json`（Java 与 Node 都必须算出同样的值）。
