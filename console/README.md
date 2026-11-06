# console

miniMac App 引桥 控制台（Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus）。

骨架阶段：页面结构完整，数据全部来自 `src/mock/`（明确标注 MOCK）；仅顶部服务端状态调用真实的只读健康接口。

```bash
npm ci
npm run dev     # http://127.0.0.1:5173，/__mmab 代理到 127.0.0.1:8443
npm run build   # vue-tsc 类型检查 + 生产构建到 dist/
```

页面：应用总览、实例详情、生命周期、版本兼容、安全与审计。
