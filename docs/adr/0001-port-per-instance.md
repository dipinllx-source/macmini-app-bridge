# ADR-0001：同一 IP、每实例独立端口作为访问入口

- 状态：已接受（2026-11-07）

## 背景

需要在可信内网中通过浏览器访问多个桌面应用的原版界面，访问端不希望配置 DNS 或 hosts。

## 备选

1. **子域名**：每实例独立 origin，Cookie 可彻底隔离；依赖内网 DNS 泛解析或各终端 hosts。
2. **同端口按路径**（`/apps/<id>/`）：不依赖 DNS；但原版应用假设运行在根路径（绝对资源路径、动态导入、前端路由、自带 Service Worker），且所有应用同源、浏览器层不隔离。
3. **同一 IP 每实例独立端口**：不依赖 DNS；原版应用在根路径运行；页面存储、Service Worker、DOM 按 origin 隔离。

## 决定

采用方案 3。控制台 8443，实例 8444 起，8446–8459 预留（建议值）。一个 Java 进程开多个 HTTPS 监听。

## 后果

- Cookie 与 SameSite 不按端口隔离：每端口独立 `__Host-` Cookie 名并只认自己的 Cookie；所有写操作与 WS 精确校验 Origin（含端口）；敏感资源要求 `Sec-Fetch-Site: same-origin` 与 `Cross-Origin-Resource-Policy: same-origin`。
- 控制台进入实例需一次性票据经表单 POST 换取实例会话（设计 §5.3）。
- 将来有内网 DNS 时可平移为子域名，网关、租约、适配器协议不变。
