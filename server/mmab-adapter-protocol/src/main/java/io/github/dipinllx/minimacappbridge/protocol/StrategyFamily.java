package io.github.dipinllx.minimacappbridge.protocol;

/**
 * 桥接策略族（设计文档 §14.1）。数量少、平台维护；新增策略族需独立安全评审。
 */
public enum StrategyFamily {
    /** S1：主进程注入，接触 ipcMain（参考：千问）。 */
    S1_ELECTRON_MAIN_INJECT("electron-main-inject"),
    /** S2：renderer CDP + 端口中继（参考：Codex）。 */
    S2_ELECTRON_RENDERER_CDP("electron-renderer-cdp"),
    /** S3：应用自带本地 Web UI，仅需鉴权代理。 */
    S3_LOCAL_WEB_PROXY("local-web-proxy"),
    /** S4：应用官方远控 / 服务接口 + 平台提供前端。 */
    S4_OFFICIAL_REMOTE_API("official-remote-api");

    private final String id;

    StrategyFamily(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
