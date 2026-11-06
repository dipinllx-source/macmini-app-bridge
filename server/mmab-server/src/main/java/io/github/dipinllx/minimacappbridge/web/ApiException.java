package io.github.dipinllx.minimacappbridge.web;

import io.github.dipinllx.minimacappbridge.protocol.ErrorCode;
import org.springframework.http.HttpStatus;

/** 控制器抛出的结构化拒绝；响应体只含错误码与固定说明。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public ApiException(HttpStatus status, ErrorCode code) {
        super(code.name());
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
