package io.github.dipinllx.minimacappbridge.protocol.codec;

import io.github.dipinllx.minimacappbridge.protocol.AdapterProtocol;
import io.github.dipinllx.minimacappbridge.protocol.ErrorCode;
import io.github.dipinllx.minimacappbridge.protocol.ProtocolException;
import io.github.dipinllx.minimacappbridge.protocol.StrictJson;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.Entry;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvArray;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvBigInt;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvBool;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvBytes;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvDate;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvError;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvMap;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvNull;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvNumber;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvObject;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvPort;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvSet;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvString;
import io.github.dipinllx.minimacappbridge.protocol.codec.SvValue.SvUndefined;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * mmab-sv1：平台结构化值 codec（规范见 {@code sdk/protocol/codec-sv1.md}，JS 实现见 {@code sdk/web/codec.mjs}）。
 *
 * <p>编码后的 JSON 中，<strong>每个 JSON 对象都是带 {@code "$t"} 的类型节点</strong>，普通对象也包在
 * {@code {"$t":"object","v":{...}}} 里，因此业务数据无法伪造类型标记。不支持的类型、超深嵌套、
 * 原型污染键一律 {@link ErrorCode#CODEC_UNSUPPORTED}，结构错误为 {@link ErrorCode#MALFORMED_FRAME}，
 * 不静默转空对象。
 */
public final class StructuredCodec {

    /** 拒绝的对象键（原型污染防护，设计 §9.4）。 */
    public static final Set<String> FORBIDDEN_KEYS = Set.of("__proto__", "constructor", "prototype");

    public static final Set<String> ERROR_NAMES = Set.of("Error", "EvalError", "RangeError", "ReferenceError",
            "SyntaxError", "TypeError", "URIError");

    private static final Pattern BIGINT = Pattern.compile("^-?(0|[1-9][0-9]{0,4095})$");
    private static final Pattern PORT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final double MAX_SAFE = 9007199254740991d;

    private final int maxDepth;

    public StructuredCodec() {
        this(AdapterProtocol.MAX_NESTING_DEPTH);
    }

    public StructuredCodec(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    // ------------------------------------------------------------------ decode

    public SvValue decode(String text) {
        return fromNode(StrictJson.CODEC.parse(text), 0);
    }

    /** 只校验，不保留结果；网关用它对 payload 做深度 / 类型闸门。 */
    public void validate(String text) {
        decode(text);
    }

    private SvValue fromNode(JsonNode n, int depth) {
        if (n.isNull()) {
            return new SvNull();
        }
        if (n.isBoolean()) {
            return new SvBool(n.asBoolean());
        }
        if (n.isString()) {
            return new SvString(n.stringValue());
        }
        if (n.isNumber()) {
            return new SvNumber(n.doubleValue());
        }
        if (n.isArray()) {
            checkDepth(depth + 1);
            List<SvValue> items = new ArrayList<>(n.size());
            for (JsonNode item : n.values()) {
                items.add(fromNode(item, depth + 1));
            }
            return new SvArray(items);
        }
        if (!n.isObject()) {
            throw malformed("unexpected json node");
        }
        JsonNode tagNode = n.get("$t");
        if (tagNode == null || !tagNode.isString()) {
            throw malformed("object without $t tag");
        }
        String tag = tagNode.stringValue();
        switch (tag) {
            case "undef" -> {
                onlyKeys(n, "$t");
                return new SvUndefined();
            }
            case "num" -> {
                onlyKeys(n, "$t", "v");
                String v = string(n, "v");
                return switch (v) {
                    case "NaN" -> new SvNumber(Double.NaN);
                    case "Infinity" -> new SvNumber(Double.POSITIVE_INFINITY);
                    case "-Infinity" -> new SvNumber(Double.NEGATIVE_INFINITY);
                    case "-0" -> new SvNumber(-0.0d);
                    default -> throw malformed("invalid special number");
                };
            }
            case "bigint" -> {
                onlyKeys(n, "$t", "v");
                String v = string(n, "v");
                if (!BIGINT.matcher(v).matches()) {
                    throw malformed("invalid bigint");
                }
                return new SvBigInt(new BigInteger(v));
            }
            case "date" -> {
                onlyKeys(n, "$t", "v");
                JsonNode v = n.get("v");
                if (v == null) {
                    throw malformed("date without v");
                }
                if (v.isNull()) {
                    return new SvDate(null);
                }
                if (!v.isNumber()) {
                    throw malformed("date v must be number|null");
                }
                return new SvDate(v.doubleValue());
            }
            case "bytes" -> {
                onlyKeys(n, "$t", "k", "v");
                BytesKind kind = BytesKind.fromName(string(n, "k"))
                        .orElseThrow(() -> unsupported("unsupported binary view"));
                byte[] data;
                try {
                    data = Base64.getDecoder().decode(string(n, "v"));
                } catch (IllegalArgumentException e) {
                    throw malformed("invalid base64");
                }
                if (data.length % kind.elementSize() != 0) {
                    throw malformed("byte length not aligned");
                }
                return new SvBytes(kind, data);
            }
            case "object" -> {
                onlyKeys(n, "$t", "v");
                checkDepth(depth + 1);
                JsonNode v = n.get("v");
                if (v == null || !v.isObject()) {
                    throw malformed("object v must be a json object");
                }
                Map<String, SvValue> fields = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> e : v.properties()) {
                    if (FORBIDDEN_KEYS.contains(e.getKey())) {
                        throw unsupported("forbidden key");
                    }
                    fields.put(e.getKey(), fromNode(e.getValue(), depth + 1));
                }
                return new SvObject(fields);
            }
            case "map" -> {
                onlyKeys(n, "$t", "v");
                checkDepth(depth + 1);
                List<Entry> entries = new ArrayList<>();
                for (JsonNode pair : array(n, "v").values()) {
                    if (!pair.isArray() || pair.size() != 2) {
                        throw malformed("map entry must be [k, v]");
                    }
                    entries.add(new Entry(fromNode(pair.get(0), depth + 1), fromNode(pair.get(1), depth + 1)));
                }
                return new SvMap(entries);
            }
            case "set" -> {
                onlyKeys(n, "$t", "v");
                checkDepth(depth + 1);
                List<SvValue> items = new ArrayList<>();
                for (JsonNode item : array(n, "v").values()) {
                    items.add(fromNode(item, depth + 1));
                }
                return new SvSet(items);
            }
            case "error" -> {
                onlyKeys(n, "$t", "name", "message");
                String name = string(n, "name");
                if (!ERROR_NAMES.contains(name)) {
                    throw malformed("unknown error name");
                }
                return new SvError(name, string(n, "message"));
            }
            case "port" -> {
                onlyKeys(n, "$t", "id");
                String id = string(n, "id");
                if (!PORT_ID.matcher(id).matches()) {
                    throw malformed("invalid port id");
                }
                return new SvPort(id);
            }
            default -> throw unsupported("unknown tag");
        }
    }

    // ------------------------------------------------------------------ encode

    public String encode(SvValue value) {
        return StrictJson.CODEC.write(toNode(value, 0));
    }

    private JsonNode toNode(SvValue value, int depth) {
        StrictJson json = StrictJson.CODEC;
        return switch (value) {
            case SvNull ignored -> JsonNodeFactory.instance.nullNode();
            case SvBool b -> JsonNodeFactory.instance.booleanNode(b.value());
            case SvString s -> JsonNodeFactory.instance.stringNode(s.value());
            case SvNumber num -> number(num.value());
            case SvUndefined ignored -> tag("undef");
            case SvBigInt big -> tag("bigint").put("v", big.value().toString());
            case SvDate d -> {
                ObjectNode t = tag("date");
                if (d.epochMillis() == null || d.epochMillis().isNaN()) {
                    t.putNull("v");
                } else {
                    t.set("v", number(d.epochMillis()));
                }
                yield t;
            }
            case SvBytes bytes -> tag("bytes").put("k", bytes.kind().name())
                    .put("v", Base64.getEncoder().encodeToString(bytes.data()));
            case SvArray arr -> {
                checkDepth(depth + 1);
                ArrayNode a = json.array();
                for (SvValue item : arr.items()) {
                    a.add(toNode(item, depth + 1));
                }
                yield a;
            }
            case SvObject obj -> {
                checkDepth(depth + 1);
                ObjectNode t = tag("object");
                ObjectNode v = t.putObject("v");
                for (Map.Entry<String, SvValue> e : obj.fields().entrySet()) {
                    if (FORBIDDEN_KEYS.contains(e.getKey())) {
                        throw unsupported("forbidden key");
                    }
                    v.set(e.getKey(), toNode(e.getValue(), depth + 1));
                }
                yield t;
            }
            case SvMap map -> {
                checkDepth(depth + 1);
                ObjectNode t = tag("map");
                ArrayNode v = t.putArray("v");
                for (Entry e : map.entries()) {
                    ArrayNode pair = v.addArray();
                    pair.add(toNode(e.key(), depth + 1));
                    pair.add(toNode(e.value(), depth + 1));
                }
                yield t;
            }
            case SvSet set -> {
                checkDepth(depth + 1);
                ObjectNode t = tag("set");
                ArrayNode v = t.putArray("v");
                for (SvValue item : set.items()) {
                    v.add(toNode(item, depth + 1));
                }
                yield t;
            }
            case SvError err -> tag("error").put("name", ERROR_NAMES.contains(err.name()) ? err.name() : "Error")
                    .put("message", err.message());
            case SvPort port -> {
                if (!PORT_ID.matcher(port.id()).matches()) {
                    throw malformed("invalid port id");
                }
                yield tag("port").put("id", port.id());
            }
        };
    }

    private static JsonNode number(double d) {
        if (Double.isNaN(d)) {
            return tag("num").put("v", "NaN");
        }
        if (Double.isInfinite(d)) {
            return tag("num").put("v", d > 0 ? "Infinity" : "-Infinity");
        }
        if (d == 0 && Double.doubleToRawLongBits(d) != 0L) {
            return tag("num").put("v", "-0");
        }
        if (d == Math.rint(d) && Math.abs(d) <= MAX_SAFE) {
            return JsonNodeFactory.instance.numberNode((long) d);
        }
        return JsonNodeFactory.instance.numberNode(d);
    }

    private static ObjectNode tag(String t) {
        ObjectNode n = StrictJson.CODEC.object();
        n.put("$t", t);
        return n;
    }

    // ------------------------------------------------------------------ helpers

    private void checkDepth(int depth) {
        if (depth > maxDepth) {
            throw unsupported("nesting depth exceeds " + maxDepth);
        }
    }

    private static void onlyKeys(JsonNode n, String... allowed) {
        Set<String> ok = Set.of(allowed);
        for (String name : n.propertyNames()) {
            if (!ok.contains(name)) {
                throw malformed("unexpected key in tagged node");
            }
        }
    }

    private static String string(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isString()) {
            throw malformed(field + " must be a string");
        }
        return v.stringValue();
    }

    private static JsonNode array(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isArray()) {
            throw malformed(field + " must be an array");
        }
        return v;
    }

    private static ProtocolException malformed(String detail) {
        return new ProtocolException(ErrorCode.MALFORMED_FRAME, detail);
    }

    private static ProtocolException unsupported(String detail) {
        return new ProtocolException(ErrorCode.CODEC_UNSUPPORTED, detail);
    }
}
