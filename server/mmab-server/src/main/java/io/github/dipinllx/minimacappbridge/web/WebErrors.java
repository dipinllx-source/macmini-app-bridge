package io.github.dipinllx.minimacappbridge.web;

import io.github.dipinllx.minimacappbridge.protocol.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 统一错误体：{@code {"error":{"code":"...","message":"固定说明"}}}，不回显请求内容、路径或异常正文。
 */
@RestControllerAdvice
public class WebErrors {

    public static Map<String, Object> body(ErrorCode code) {
        return Map.of("error", Map.of("code", code.name(), "message", code.summary()));
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ErrorCode code) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        String json = "{\"error\":{\"code\":\"" + code.name() + "\",\"message\":\"" + code.summary() + "\"}}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
    }

    /** 实例端口无会话时给浏览器的最小提示页（设计 §5.5：无票据直连仅见登录提示）。 */
    public static Mono<Void> writeLoginHint(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(new MediaType("text", "html", StandardCharsets.UTF_8));
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>miniMac App 引桥</title>"
                + "</head><body><h1>需要从控制台开窗</h1><p>该地址只接受由控制台签发的一次性开窗票据。"
                + "请回到 miniMac App 引桥控制台，点击“打开应用”。</p></body></html>";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(html.getBytes(StandardCharsets.UTF_8))));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> onApi(ApiException e) {
        return ResponseEntity.status(e.status()).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body(e.code()));
    }
}
