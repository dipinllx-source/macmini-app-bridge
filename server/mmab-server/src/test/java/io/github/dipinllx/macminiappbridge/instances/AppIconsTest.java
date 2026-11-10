package io.github.dipinllx.macminiappbridge.instances;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 头像图标路径白名单：只允许 /Applications 下 .app 包内的 Contents/Resources/*.icns。 */
class AppIconsTest {

    @Test
    void onlyIconsInsideAnApplicationBundleAreAccepted() throws Exception {
        assertThat(AppIcons.resolve("/Applications/X.app/Contents/MacOS/X", "Contents/Resources/../../../../etc/passwd.icns")).isEmpty();
        assertThat(AppIcons.resolve("/Applications/X.app/Contents/MacOS/X", "/etc/hosts")).isEmpty();
        assertThat(AppIcons.resolve("/tmp/X.app/Contents/MacOS/X", "Contents/Resources/a.icns")).isEmpty();
        assertThat(AppIcons.resolve("none", "Contents/Resources/a.icns")).isEmpty();
        assertThat(AppIcons.resolve("/Applications/X.app/Contents/MacOS/X", null)).isEmpty();
        assertThat(AppIcons.resolve("/Applications/Does-Not-Exist.app/Contents/MacOS/X", "Contents/Resources/a.icns")).isEmpty();
    }
}
