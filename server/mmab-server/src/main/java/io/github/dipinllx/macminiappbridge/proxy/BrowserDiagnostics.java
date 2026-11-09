package io.github.dipinllx.macminiappbridge.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 浏览器断线诊断（reliable-personal-entry D1）：页面在 WS 关闭 / 卸载 / 重新加载时上报的元数据。
 *
 * <p>只接受白名单字段，值只能是枚举、数字、布尔或受限字符集的名称（应用的消息类型 / IPC 通道名），
 * 任何多余字段、超长或越界值整条拒绝——审计里永远不会出现消息内容、令牌或任意文本。
 */
public final class BrowserDiagnostics {

    public static final int MAX_BODY_BYTES = 4096;
    private static final Set<String> EVENTS = Set.of("close", "unload", "load");
    private static final Set<String> NAV = Set.of("navigate", "reload", "back_forward", "prerender");
    private static final Set<String> VIS = Set.of("visible", "hidden", "prerender");
    private static final Pattern REASON = Pattern.compile("[A-Z_]{1,40}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.:/-]{1,64}");
    private static final int MAX_RECENT = 16;
    private static final long MAX_MILLIS = 400L * 24 * 3600 * 1000;

    private static final Set<String> TOP = Set.of("e", "code", "clean", "reason", "nav", "vis", "up", "ws", "unl", "recent", "prev");
    private static final Set<String> PREV = Set.of("e", "code", "clean", "reason", "vis", "up", "ws", "recent");

    private BrowserDiagnostics() {
    }

    /** @return 事件类型与可写入审计的 detail；字段不合规时为空 */
    public static Optional<Map.Entry<String, String>> parse(JsonNode n) {
        StringBuilder detail = new StringBuilder();
        if (!append(n, TOP, detail, "")) {
            return Optional.empty();
        }
        JsonNode prev = n.get("prev");
        if (prev != null) {
            if (!prev.isObject() || !append(prev, PREV, detail, "prev.")) {
                return Optional.empty();
            }
        }
        return Optional.of(Map.entry(n.get("e").stringValue(), detail.toString().trim()));
    }

    private static boolean append(JsonNode n, Set<String> allowed, StringBuilder out, String prefix) {
        if (n == null || !n.isObject() || n.get("e") == null || !n.get("e").isString()
                || !EVENTS.contains(n.get("e").stringValue())) {
            return false;
        }
        List<String> names = new ArrayList<>();
        n.propertyNames().forEach(names::add);
        for (String k : names) {
            if (!allowed.contains(k)) {
                return false;
            }
            if (k.equals("prev")) {
                continue;
            }
            JsonNode v = n.get(k);
            String text = switch (k) {
                case "e" -> v.stringValue();
                case "nav" -> enumValue(v, NAV);
                case "vis" -> enumValue(v, VIS);
                case "reason" -> v.isString() && REASON.matcher(v.stringValue()).matches() ? v.stringValue() : null;
                case "code" -> v.isIntegralNumber() && v.asLong() >= 0 && v.asLong() <= 4999 ? Long.toString(v.asLong()) : null;
                case "up", "ws" -> v.isIntegralNumber() && v.asLong() >= 0 && v.asLong() <= MAX_MILLIS ? Long.toString(v.asLong()) : null;
                case "clean", "unl" -> v.isBoolean() ? Boolean.toString(v.asBoolean()) : null;
                case "recent" -> recent(v);
                default -> null;
            };
            if (text == null) {
                return false;
            }
            out.append(prefix).append(k).append('=').append(text).append(' ');
        }
        return true;
    }

    private static String enumValue(JsonNode v, Set<String> allowed) {
        return v.isString() && allowed.contains(v.stringValue()) ? v.stringValue() : null;
    }

    private static String recent(JsonNode v) {
        if (!v.isArray() || v.size() > MAX_RECENT) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (JsonNode x : v) {
            if (!x.isString() || !NAME.matcher(x.stringValue()).matches()) {
                return null;
            }
            items.add(x.stringValue());
        }
        return items.isEmpty() ? "-" : String.join(",", items);
    }
}
