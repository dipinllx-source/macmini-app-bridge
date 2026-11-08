package io.github.dipinllx.macminiappbridge.protocol;

import java.util.Objects;

/**
 * 协议层拒绝。{@link #getMessage()} 只含固定说明 / 内部细节类别，不含 payload 正文。
 */
public class ProtocolException extends RuntimeException {

    private final ErrorCode code;

    public ProtocolException(ErrorCode code, String detail) {
        super(code + ": " + detail);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ProtocolException(ErrorCode code, String detail, Throwable cause) {
        super(code + ": " + detail, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }
}
