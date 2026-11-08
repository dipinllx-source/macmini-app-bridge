package io.github.dipinllx.macminiappbridge.web;

/** 请求属性键：由 {@link GuardFilter} 写入，控制器只读。 */
public final class MmabAttributes {

    public static final String ROLE = "mmab.role";
    public static final String CONSOLE_SESSION = "mmab.consoleSession";
    public static final String INSTANCE_SESSION = "mmab.instanceSession";

    /** 控制台会话 Cookie 名（设计 §5.2：每端口不同名）。 */
    public static final String CONSOLE_COOKIE = "__Host-mmab-console";

    /** 控制台写请求的 CSRF 头。 */
    public static final String CSRF_HEADER = "X-MMAB-CSRF";

    private MmabAttributes() {
    }

    /** 实例会话 Cookie 名：{@code __Host-mmab-i-<instanceId>}。 */
    public static String instanceCookie(String instanceId) {
        return "__Host-mmab-i-" + instanceId;
    }
}
