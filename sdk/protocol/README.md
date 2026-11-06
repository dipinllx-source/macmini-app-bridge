# sdk/protocol

MMAB Adapter Protocol v1（M1 草案，已有 Java / Node 两端实现与契约测试）。

| 文件 | 内容 | Java 对应 | 契约测试 |
|---|---|---|---|
| `envelope.schema.json` | 数据面 envelope（含 `operation`、`error`、错误码枚举） | `protocol.Envelope` / `EnvelopeJson` | `EnvelopeSchemaContractTest`、`wire.test.mjs` |
| `adapter.schema.json` | `adapter.yaml` 结构（能力四态、操作白名单） | `instances.AdapterDescriptorLoader` | `AdapterDescriptorTest` + 构建时 JSON Schema 校验 |
| `codec-sv1.md` | 结构化值 codec mmab-sv1 | `protocol.codec.StructuredCodec` | `StructuredCodecContractTest`、`sdk/web/codec.test.mjs` |
| `adapter-wire-v1.md` | 平台 ↔ 适配器内部认证与帧格式 | `protocol.wire.WireAuth`、`instances.ProcessAdapterClient` | `WireAuthTest`、`ProcessAdapterClientTest`、`wire.test.mjs`、`adapters/mock/tests` |
| `fixtures/` | 共用 fixture：codec 往返 / 拒绝用例、envelope 正反例、线上认证固定向量 | — | 两端同时读取 |

Schema 与 Java 类型必须同步修改；字段集合、`messageKind`、错误码枚举由 `EnvelopeSchemaContractTest` 自动比对。

## 浏览器 ↔ 平台（实例端口 WS `/__mmab/bridge/ws`）

浏览器帧**不含** instance / generation / lease / fence：这些由服务端按实例会话填入 envelope，浏览器无法伪造。
带额外字段的帧按 `MALFORMED_FRAME` 拒绝。

```text
→ {"kind":"REQUEST","op":"mock.echo","cid":"1","payload":"<mmab-sv1 文本>"}
→ {"kind":"HEARTBEAT"}                                   // 续租（shim 每 15 秒）
← {"kind":"RESPONSE","cid":"1","payload":"..."}
← {"kind":"RESPONSE","cid":"1","error":{"code":"UNKNOWN_OPERATION","message":"..."}}
← {"kind":"EVENT","op":"mock.tick","payload":"..."}
← {"kind":"HEARTBEAT"} | {"kind":"ERROR","error":{...}}
```

关闭码：`1008` + 错误码（会话 / 租约失效、generation 变化、慢客户端 `QUEUE_LIMIT`）；`1013 LEASE_OCCUPIED`
（同一租约的第二个标签页，第一个不受影响）；`1009`（单帧超过 1 MiB）。

## 网关判定顺序（每个 REQUEST）

1. 会话仍有效、租约仍是当前租约、generation 未变 → 否则关闭连接（`1008`）。
2. 操作在 `adapter.yaml` 的 `operations` 中声明为 `request`，且位于适配器命名空间（`appId` 去掉 `-`），
   `mmab.*` 保留给平台 → 否则 `UNKNOWN_OPERATION`。
3. 该操作的能力状态为 `SUPPORTED` → 否则 `CAPABILITY_DISABLED`（UNVERIFIED / DISABLED / UNSUPPORTED 一律关闭）。
4. payload 通过 mmab-sv1 校验（深度 ≤ 32、无原型污染键、无未知类型）→ 否则 `CODEC_UNSUPPORTED` / `MALFORMED_FRAME`。
5. 待决请求 ≤ 256；适配器队列高水位时暂停读取浏览器帧；超过 deadline 回 `DEADLINE_EXCEEDED`（执行状态未知，迟到结果丢弃）。
6. 适配器自己再校验一遍 fence 与操作（见 `adapter-wire-v1.md`）。
