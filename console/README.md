# console

miniMac App 引桥 控制台（Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus）。

M1 状态：

- 「开窗（M1）」页面调用真实接口：操作者登录 → 实例列表 → 「打开应用」签发一次性票据，并以表单 POST
  在新标签提交到实例端口的 `/__mmab/session/exchange`。CSRF token 只在 `src/api/bridge.ts` 模块内存中，
  票据只存在于那一次表单提交里，都不进 Pinia / localStorage / URL。
- 其余页面（总览、实例详情、生命周期、版本兼容、安全与审计）仍是 `src/mock/` 的 MOCK 数据。

```bash
npm ci
npm run build   # vue-tsc 类型检查 + 生产构建到 dist/
```

运行：由 mmab-server 直接提供构建产物（`mmab.web.console-static-dir=console/dist`），这样页面与平台 API 同源，
Origin / CSRF / Sec-Fetch-Site 校验才成立。`npm run dev` 的 Vite 代理会改变 Origin，只适合看界面，
写接口会被服务端按设计拒绝（ORIGIN_REJECTED）。
