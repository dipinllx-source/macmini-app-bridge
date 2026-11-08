package io.github.dipinllx.macminiappbridge.protocol;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * envelope 与 JSON 的严格互转，字段集合与 {@code sdk/protocol/envelope.schema.json} 一致：
 * 未知字段、类型不符、超范围整数一律 {@link ErrorCode#MALFORMED_FRAME}。
 */
public final class EnvelopeJson {

    static final Set<String> FIELDS = Set.of("protocolVersion", "profileId", "instanceId", "processGeneration",
            "connectionId", "leaseId", "fencingToken", "streamId", "sequence", "correlationId", "messageKind",
            "operation", "deadline", "codecVersion", "payload", "error");

    private static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    private EnvelopeJson() {
    }

    public static ObjectNode toNode(Envelope e) {
        ObjectNode n = StrictJson.WIRE.object();
        n.put("protocolVersion", e.protocolVersion().toString());
        n.put("profileId", e.profileId());
        n.put("instanceId", e.instanceId());
        n.put("processGeneration", e.processGeneration());
        n.put("connectionId", e.connectionId());
        n.put("leaseId", e.leaseId());
        n.put("fencingToken", e.fencingToken());
        if (e.streamId() != null) {
            n.put("streamId", e.streamId());
        }
        n.put("sequence", e.sequence());
        if (e.correlationId() != null) {
            n.put("correlationId", e.correlationId());
        }
        n.put("messageKind", e.messageKind().name());
        if (e.operation() != null) {
            n.put("operation", e.operation());
        }
        n.put("deadline", e.deadline().truncatedTo(ChronoUnit.MILLIS).toString());
        n.put("codecVersion", e.codecVersion());
        n.put("payload", e.payload());
        if (e.error() != null) {
            ObjectNode err = n.putObject("error");
            err.put("code", e.error().code().name());
            err.put("message", e.error().message());
        }
        return n;
    }

    public static String write(Envelope e) {
        return StrictJson.WIRE.write(toNode(e));
    }

    public static Envelope read(String json) {
        return fromNode(StrictJson.WIRE.parse(json));
    }

    public static Envelope fromNode(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw malformed("envelope must be an object");
        }
        for (String name : n.propertyNames()) {
            if (!FIELDS.contains(name)) {
                throw malformed("unknown field");
            }
        }
        try {
            ProtocolVersion version = ProtocolVersion.parse(text(n, "protocolVersion", true));
            MessageKind kind;
            try {
                kind = MessageKind.valueOf(text(n, "messageKind", true));
            } catch (IllegalArgumentException e) {
                throw malformed("unknown messageKind");
            }
            Instant deadline;
            try {
                deadline = Instant.parse(text(n, "deadline", true));
            } catch (DateTimeParseException e) {
                throw malformed("invalid deadline");
            }
            EnvelopeError error = null;
            JsonNode errNode = n.get("error");
            if (errNode != null && !errNode.isNull()) {
                if (!errNode.isObject()) {
                    throw malformed("error must be an object");
                }
                for (String name : errNode.propertyNames()) {
                    if (!name.equals("code") && !name.equals("message")) {
                        throw malformed("unknown error field");
                    }
                }
                ErrorCode code;
                try {
                    code = ErrorCode.valueOf(text(errNode, "code", true));
                } catch (IllegalArgumentException e) {
                    throw malformed("unknown error code");
                }
                error = new EnvelopeError(code, text(errNode, "message", false));
            }
            return new Envelope(version,
                    text(n, "profileId", true),
                    text(n, "instanceId", true),
                    integer(n, "processGeneration"),
                    text(n, "connectionId", true),
                    text(n, "leaseId", true),
                    integer(n, "fencingToken"),
                    text(n, "streamId", false),
                    integer(n, "sequence"),
                    text(n, "correlationId", false),
                    kind,
                    text(n, "operation", false),
                    deadline,
                    text(n, "codecVersion", true),
                    text(n, "payload", true),
                    error);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw malformed("invariant violated");
        }
    }

    private static String text(JsonNode n, String field, boolean required) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            if (required) {
                throw malformed("missing " + field);
            }
            return null;
        }
        if (!v.isString()) {
            throw malformed(field + " must be a string");
        }
        return v.stringValue();
    }

    private static long integer(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToExactIntegral()) {
            throw malformed(field + " must be an integer");
        }
        long value = v.longValue();
        if (value < 0 || value > MAX_SAFE_INTEGER) {
            throw malformed(field + " out of range");
        }
        return value;
    }

    private static ProtocolException malformed(String detail) {
        return new ProtocolException(ErrorCode.MALFORMED_FRAME, detail);
    }
}
