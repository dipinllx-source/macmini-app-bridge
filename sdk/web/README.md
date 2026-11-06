# sdk/web

浏览器 / 应用内 JS 公共件（规划，M1–M3）。来源于两个原型桥的已验证经验：

- 非安全上下文补齐：`crypto.randomUUID`、SHA-256 digest、`clipboard.writeText`、`mediaDevices` 占位（不提供 getUserMedia）
- preload 同世界兼容：contextBridge 对同值只读属性放行
- 结构化类型 codec 与嵌套 MessagePort 中继
- 有界调度队列与背压
- 断线、标签占用、启动超时的可见提示

当前为空，尚未实现。
