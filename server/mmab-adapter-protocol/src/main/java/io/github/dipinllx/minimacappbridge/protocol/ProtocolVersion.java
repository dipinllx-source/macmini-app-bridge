package io.github.dipinllx.minimacappbridge.protocol;

import java.util.Objects;

/**
 * Adapter Protocol 版本（major.minor）。未知 major 一律拒绝连接（设计 §10.3）。
 */
public record ProtocolVersion(int major, int minor) implements Comparable<ProtocolVersion> {

    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("protocol version must be non-negative");
        }
    }

    /** 解析 {@code "1.0"} 形式；不接受预发布后缀。 */
    public static ProtocolVersion parse(String text) {
        Objects.requireNonNull(text, "text");
        String[] parts = text.trim().split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("expected <major>.<minor>: " + text);
        }
        try {
            return new ProtocolVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected <major>.<minor>: " + text, e);
        }
    }

    /** 同 major 视为可协商；minor 差异由能力协商处理。 */
    public boolean isCompatibleWith(ProtocolVersion other) {
        return other != null && other.major == major;
    }

    @Override
    public int compareTo(ProtocolVersion o) {
        int c = Integer.compare(major, o.major);
        return c != 0 ? c : Integer.compare(minor, o.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
