package io.github.dipinllx.macminiappbridge.protocol.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolException;
import io.github.dipinllx.macminiappbridge.protocol.RepoFiles;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.Entry;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvArray;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvBigInt;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvBool;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvBytes;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvDate;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvError;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvMap;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvNull;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvNumber;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvObject;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvPort;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvSet;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvString;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue.SvUndefined;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** mmab-sv1 往返与拒绝用例；fixture 与 JS 测试（sdk/web/codec.test.mjs）共用。 */
class StructuredCodecContractTest {

    private static final JsonMapper PLAIN = new JsonMapper();
    private final StructuredCodec codec = new StructuredCodec();
    private final JsonNode fixtures = PLAIN.readTree(RepoFiles.read("sdk/protocol/fixtures/codec-sv1.json"));

    @Test
    void validFixturesRoundTripCanonically() {
        int count = 0;
        for (JsonNode c : fixtures.get("valid").values()) {
            String name = c.get("name").stringValue();
            String text = PLAIN.writeValueAsString(c.get("encoded"));
            SvValue v = codec.decode(text);
            String again = codec.encode(v);
            assertThat(again).as(name).isEqualTo(text);
            assertThat(codec.decode(again)).as(name).isEqualTo(v);
            count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(25);
    }

    @Test
    void invalidFixturesAreRejectedWithExpectedCode() {
        int count = 0;
        for (JsonNode c : fixtures.get("invalid").values()) {
            String name = c.get("name").stringValue();
            String text = c.has("encodedText") ? c.get("encodedText").stringValue()
                    : PLAIN.writeValueAsString(c.get("encoded"));
            ErrorCode expected = ErrorCode.valueOf(c.get("error").stringValue());
            assertThatThrownBy(() -> codec.decode(text)).as(name).isInstanceOf(ProtocolException.class)
                    .extracting(t -> ((ProtocolException) t).code()).isEqualTo(expected);
            count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(15);
    }

    @Test
    void javaModelRoundTripsAllTypes() {
        Map<String, SvValue> fields = new LinkedHashMap<>();
        fields.put("n", new SvNumber(-0.0d));
        fields.put("nan", new SvNumber(Double.NaN));
        fields.put("big", new SvBigInt(new BigInteger("-98765432109876543210")));
        fields.put("when", new SvDate(1_762_473_600_123d));
        fields.put("bad", new SvDate(null));
        fields.put("buf", new SvBytes(BytesKind.Uint16Array, new byte[] {1, 0, 2, 0}));
        fields.put("map", new SvMap(List.of(new Entry(new SvString("k"), new SvSet(List.of(new SvBool(true)))))));
        fields.put("err", new SvError("RangeError", "out"));
        fields.put("port", new SvPort("p_9"));
        fields.put("u", new SvUndefined());
        fields.put("list", new SvArray(List.of(new SvNull(), new SvNumber(2.5))));
        SvValue value = new SvObject(fields);
        assertThat(codec.decode(codec.encode(value))).isEqualTo(value);
    }

    @Test
    void negativeZeroIsNotZero() {
        assertThat(codec.encode(new SvNumber(-0.0d))).isEqualTo("{\"$t\":\"num\",\"v\":\"-0\"}");
        assertThat(codec.encode(new SvNumber(0.0d))).isEqualTo("0");
    }

    @Test
    void encoderRefusesForbiddenKeysAndUnknownErrorNamesAreNormalised() {
        assertThatThrownBy(() -> codec.encode(new SvObject(Map.of("__proto__", new SvNull()))))
                .isInstanceOf(ProtocolException.class)
                .extracting(t -> ((ProtocolException) t).code()).isEqualTo(ErrorCode.CODEC_UNSUPPORTED);
        assertThat(codec.encode(new SvError("CustomError", "m"))).contains("\"name\":\"Error\"");
    }

    @Test
    void depthLimitIsConfigurable() {
        StructuredCodec shallow = new StructuredCodec(2);
        assertThat(shallow.decode("[[1]]")).isInstanceOf(SvArray.class);
        assertThatThrownBy(() -> shallow.decode("[[[1]]]")).isInstanceOf(ProtocolException.class)
                .extracting(t -> ((ProtocolException) t).code()).isEqualTo(ErrorCode.CODEC_UNSUPPORTED);
    }
}
