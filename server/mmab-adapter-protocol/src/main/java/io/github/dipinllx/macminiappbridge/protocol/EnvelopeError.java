package io.github.dipinllx.macminiappbridge.protocol;

import java.util.Objects;

/**
 * envelope 中的结构化错误。{@code message} 只能是错误码的固定说明，不携带正文、路径或异常内容。
 *
 * @param code    错误码
 * @param message 固定中文说明
 */
public record EnvelopeError(ErrorCode code, String message) {

    public EnvelopeError {
        Objects.requireNonNull(code, "code");
        message = message == null ? code.summary() : message;
    }

    public static EnvelopeError of(ErrorCode code) {
        return new EnvelopeError(code, code.summary());
    }
}
