package com.holtherndon.bazelviz.format.session.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonArray;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonBool;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNull;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNumber;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonString;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    @Test
    void parsesEveryValueKind() {
        JsonValue value = JsonReader.parse(
                """
                {"s":"x","n":-12.5e3,"i":42,"t":true,"f":false,"z":null,"a":[1,"two",[]],"o":{"k":{}}}
                """);

        assertThat(value).isInstanceOf(JsonObject.class);
        JsonObject object = (JsonObject) value;
        assertThat(object.member("s")).contains(new JsonString("x"));
        assertThat(object.member("n")).contains(new JsonNumber("-12.5e3"));
        assertThat(object.member("i")).contains(new JsonNumber("42"));
        assertThat(object.member("t")).contains(JsonBool.TRUE);
        assertThat(object.member("f")).contains(JsonBool.FALSE);
        assertThat(object.member("a")).contains(new JsonArray(
                List.of(new JsonNumber("1"), new JsonString("two"), new JsonArray(List.of()))));
    }

    @Test
    void anExplicitNullIsIndistinguishableFromAnAbsentMember() {
        JsonObject object = (JsonObject) JsonReader.parse("{\"present\":null}");

        // The key is there...
        assertThat(object.hasKey("present")).isTrue();
        assertThat(object.members()).containsEntry("present", JsonNull.INSTANCE);
        // ...but asking for a usable value gets the same answer as for a key that
        // was never written. Neither may become 0 or "" downstream.
        assertThat(object.member("present")).isEmpty();
        assertThat(object.member("neverWritten")).isEmpty();
    }

    @Test
    void memberOrderIsPreservedThroughAParseAndWriteCycle() {
        String source = "{\"z\":1,\"a\":2,\"m\":3}";

        assertThat(JsonWriter.writeCompact(JsonReader.parse(source))).isEqualTo(source);
    }

    @Test
    void numberLiteralsAreReemittedExactly() {
        // A newer build might write a precision this one would mangle by
        // round-tripping through double.
        String source = "{\"big\":123456789012345678901234567890,\"exact\":0.10000000000000000001}";

        assertThat(JsonWriter.writeCompact(JsonReader.parse(source))).isEqualTo(source);
    }

    @Test
    void escapesRoundTrip() {
        String awkward = "quote\" backslash\\ newline\n tab\t control\u0001 unicode\u00e9";
        String encoded = JsonWriter.writeCompact(new JsonString(awkward));

        assertThat(encoded).contains("\\\"").contains("\\\\").contains("\\n").contains("\\u0001");
        assertThat(JsonReader.parse(encoded)).isEqualTo(new JsonString(awkward));
    }

    @Test
    void decodesUnicodeEscapesIncludingSurrogatePairs() {
        assertThat(JsonReader.parse("\"\\u00e9\"")).isEqualTo(new JsonString("é"));
        assertThat(JsonReader.parse("\"\\ud83d\\ude00\"")).isEqualTo(new JsonString("\uD83D\uDE00"));
    }

    @Test
    void prettyOutputIsIndentedAndNewlineTerminated() {
        String text = JsonWriter.writePretty(new JsonObject(Map.of("a", new JsonArray(List.of(JsonNumber.of(1))))));

        assertThat(text).isEqualTo("{\n  \"a\": [\n    1\n  ]\n}\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{",
        "{\"a\":1,}",
        "[1,]",
        "{'a':1}",
        "{\"a\":01}",
        "{\"a\":+1}",
        "{\"a\":.5}",
        "{\"a\":1}trailing",
        "\"unterminated",
        "nul",
        ""
    })
    void rejectsMalformedDocuments(String source) {
        assertThatThrownBy(() -> JsonReader.parse(source)).isInstanceOf(JsonException.class);
    }

    @Test
    void rejectsDuplicateKeysRatherThanSilentlyPickingOne() {
        assertThatThrownBy(() -> JsonReader.parse("{\"a\":1,\"a\":2}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("duplicate object member 'a'");
    }

    @Test
    void rejectsUnescapedControlCharactersInStrings() {
        assertThatThrownBy(() -> JsonReader.parse("\"line\nbreak\""))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("unescaped control character");
    }

    @Test
    void enforcesTheCharacterLimitInsteadOfTruncating() throws Exception {
        String source = "[" + "1,".repeat(100) + "1]";

        assertThatThrownBy(() -> JsonReader.parse(new StringReader(source), JsonReader.DEFAULT_MAX_DEPTH, 32))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("character limit");
    }

    @Test
    void enforcesTheDepthLimit() throws Exception {
        String deep = "[".repeat(40) + "]".repeat(40);

        assertThatThrownBy(() -> JsonReader.parse(new StringReader(deep), 8, JsonReader.DEFAULT_MAX_CHARS))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("nesting deeper than");
    }

    @Test
    void numberAccessorsReportTheirOwnFailures() {
        assertThat(new JsonNumber("42").asLong()).isEqualTo(42L);
        assertThat(new JsonNumber("4.5").asDouble()).isEqualTo(4.5);
        assertThatThrownBy(() -> new JsonNumber("4.5").asLong()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> new JsonNumber("9999999999").asInt())
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("32 bits");
    }
}
