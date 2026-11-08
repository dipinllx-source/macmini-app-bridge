package io.github.dipinllx.macminiappbridge.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Java envelope 与 sdk/protocol/envelope.schema.json、fixtures 的契约一致性。 */
class EnvelopeSchemaContractTest {

    private final JsonNode schema = new tools.jackson.databind.json.JsonMapper()
            .readTree(RepoFiles.read("sdk/protocol/envelope.schema.json"));

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.values().forEach(n -> out.add(n.stringValue()));
        return out;
    }

    @Test
    void schemaFieldsMatchJava() {
        assertThat(schema.get("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(EnvelopeJson.FIELDS);
    }

    @Test
    void schemaMessageKindsMatchJava() {
        assertThat(strings(schema.get("properties").get("messageKind").get("enum")))
                .containsExactlyElementsOf(Arrays.stream(MessageKind.values()).map(Enum::name).toList());
    }

    @Test
    void schemaErrorCodesMatchJava() {
        assertThat(strings(schema.get("$defs").get("errorCode").get("enum")))
                .containsExactlyElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    @Test
    void validFixturesParseAndRoundTrip() throws IOException {
        List<Path> files = RepoFiles.list("sdk/protocol/fixtures/envelopes/valid");
        assertThat(files).hasSizeGreaterThanOrEqualTo(5);
        for (Path f : files) {
            Envelope e = EnvelopeJson.read(Files.readString(f, StandardCharsets.UTF_8));
            assertThat(EnvelopeJson.read(EnvelopeJson.write(e))).as(f.getFileName().toString()).isEqualTo(e);
        }
    }

    @Test
    void invalidFixturesAreRejected() throws IOException {
        List<Path> files = RepoFiles.list("sdk/protocol/fixtures/envelopes/invalid");
        assertThat(files).hasSizeGreaterThanOrEqualTo(5);
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            assertThatThrownBy(() -> EnvelopeJson.read(text)).as(f.getFileName().toString())
                    .isInstanceOf(ProtocolException.class)
                    .extracting(t -> ((ProtocolException) t).code()).isEqualTo(ErrorCode.MALFORMED_FRAME);
        }
    }

    @Test
    void rejectsDuplicateKeysAndTrailingTokens() {
        String ok = RepoFiles.read("sdk/protocol/fixtures/envelopes/valid/request.json").trim();
        String dup = ok.substring(0, ok.length() - 1) + ",\"instanceId\":\"other\"}";
        assertThatThrownBy(() -> EnvelopeJson.read(dup)).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> EnvelopeJson.read(ok + " {}")).isInstanceOf(ProtocolException.class);
    }
}
