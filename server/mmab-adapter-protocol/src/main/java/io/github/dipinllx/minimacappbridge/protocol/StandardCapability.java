package io.github.dipinllx.minimacappbridge.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * 平台标准能力 ID（设计文档 §14.4）。适配器按应用版本为每项声明 {@link CapabilityState}。
 */
public enum StandardCapability {
    UI_RENDER("ui.render", "原版界面可加载"),
    UI_HISTORY("ui.history", "历史会话浏览"),
    UI_COMPOSE("ui.compose", "新建任务 / 发送消息"),
    STREAM_PUSH("stream.push", "宿主推送、流式结果"),
    FILE_UPLOAD("file.upload", "附件"),
    FILE_DROP("file.drop", "拖放"),
    CLIPBOARD_WRITE("clipboard.write", "剪贴板写"),
    CLIPBOARD_READ("clipboard.read", "剪贴板读"),
    MEDIA_VOICE("media.voice", "语音（需 HTTPS）"),
    APP_GIT("app.git", "Git 类操作"),
    APP_EXTENSION("app.extension", "插件 / MCP 类能力");

    private final String id;
    private final String summary;

    StandardCapability(String id, String summary) {
        this.id = id;
        this.summary = summary;
    }

    public String id() {
        return id;
    }

    public String summary() {
        return summary;
    }

    public static Optional<StandardCapability> fromId(String id) {
        return Arrays.stream(values()).filter(c -> c.id.equals(id)).findFirst();
    }
}
