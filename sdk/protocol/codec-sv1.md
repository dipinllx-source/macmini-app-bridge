# mmab-sv1：平台结构化值 codec

envelope 的 `payload` 是 mmab-sv1 编码后的 JSON 文本（`codecVersion: "mmab-sv1"`）。
实现：`sdk/web/codec.mjs`（浏览器 + Node）、`server/mmab-adapter-protocol/.../codec/StructuredCodec.java`（Java 只用于校验和测试，
不把 payload 映射为任意 Java 类）。共用 fixture：`fixtures/codec-sv1.json`。

## 编码规则

编码结果中**每个 JSON 对象都是类型节点** `{"$t": ...}`；普通对象也包一层，所以业务数据无法伪造类型标记。

| 值 | 编码 |
|---|---|
| `null` / 布尔 / 字符串 | 原样 |
| 有限数（非 -0） | JSON 数字 |
| `NaN` / `±Infinity` / `-0` | `{"$t":"num","v":"NaN"}` 等 |
| `undefined`（含数组空洞） | `{"$t":"undef"}` |
| `BigInt` | `{"$t":"bigint","v":"-123"}`（十进制，≤ 4096 位） |
| `Date` | `{"$t":"date","v":<毫秒>}`；Invalid Date 为 `v: null` |
| `ArrayBuffer` / 11 种 TypedArray（Node `Buffer` 视为 `Uint8Array`） | `{"$t":"bytes","k":"Uint8Array","v":"<base64>"}`，只含视图自身字节 |
| 数组 | JSON 数组 |
| 普通对象（原型为 `Object.prototype` 或 `null`） | `{"$t":"object","v":{...}}` |
| `Map` / `Set` | `{"$t":"map","v":[[k,v],...]}` / `{"$t":"set","v":[...]}` |
| `Error` 及 6 种标准子类 | `{"$t":"error","name":"TypeError","message":"..."}`（不传 stack；非标准 name 归为 `Error`） |
| MessagePort 引用 | `{"$t":"port","id":"p-1"}`（端口生命周期见 `PortTable`，M1 未接入中继） |

## 明确拒绝（不静默转换）

- `CODEC_UNSUPPORTED`：函数、Symbol、`DataView`、`WeakMap`、`RegExp`、`Blob`、类实例等非普通对象、循环引用、
  嵌套深度 > 32、对象键 `__proto__` / `constructor` / `prototype`、未知类型标记。
- `MALFORMED_FRAME`：不是合法 JSON、没有 `$t` 的对象、类型节点多余 / 缺失字段、非法 bigint / base64 / 端口 ID、
  字节数不是元素大小的整数倍、未知 Error name。Java 网关还拒绝重复键与尾随内容（JS `JSON.parse` 不能检测重复键）。

## 与 structured clone 的差异（已知、不承诺）

- 共享（非循环）引用会被复制成两份，不保留同一性。
- 不支持 `Blob` / `File` / stream / `DataView` / 类实例；Error 不保留 stack 与自定义属性。
- 二进制用 base64，不适合大媒体；二进制帧格式留待后续版本。
