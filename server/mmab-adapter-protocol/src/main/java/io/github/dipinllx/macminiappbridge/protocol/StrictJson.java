package io.github.dipinllx.macminiappbridge.protocol;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 严格 JSON 读写：拒绝重复键、尾随内容，限制嵌套深度与文档长度。
 * 只产出 Jackson 树，不做任何 POJO / 多态反序列化（设计 §9.4）。
 */
public final class StrictJson {

    private final JsonMapper mapper;

    private StrictJson(int maxDepth, long maxDocumentLength) {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(maxDepth)
                        .maxDocumentLength(maxDocumentLength)
                        .maxNumberLength(400)
                        .build())
                .build();
        this.mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    /** envelope / 线上帧：结构很浅，payload 是字符串。 */
    public static final StrictJson WIRE = new StrictJson(8, AdapterProtocol.MAX_WIRE_LINE_BYTES);

    /** codec payload：逻辑深度另由 codec 限制，这里只做 JSON 层兜底。 */
    public static final StrictJson CODEC = new StrictJson(AdapterProtocol.MAX_NESTING_DEPTH * 3 + 8,
            AdapterProtocol.MAX_FRAME_BYTES);

    /** 解析；任何语法 / 约束错误统一为 {@link ErrorCode#MALFORMED_FRAME}。 */
    public JsonNode parse(String text) {
        if (text == null) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "null text");
        }
        try {
            JsonNode node = mapper.readTree(text);
            if (node == null || node.isMissingNode()) {
                throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "empty document");
            }
            return node;
        } catch (JacksonException e) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "invalid json");
        }
    }

    public String write(JsonNode node) {
        return mapper.writeValueAsString(node);
    }

    public ObjectNode object() {
        return mapper.createObjectNode();
    }

    public ArrayNode array() {
        return mapper.createArrayNode();
    }
}
