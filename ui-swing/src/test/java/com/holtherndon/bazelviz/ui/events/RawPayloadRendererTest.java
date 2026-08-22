package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The hex dump and the decoded pane, including the limits they disclose. */
class RawPayloadRendererTest {

    @Test
    @DisplayName("the hex dump shows offset, bytes and printable ASCII")
    void hexDumpLayout() {
        String dump = RawPayloadRenderer.hexDump("Hello".getBytes(StandardCharsets.US_ASCII));

        assertThat(dump).startsWith("00000000  48 65 6c 6c 6f");
        assertThat(dump).contains("|Hello|");
    }

    @Test
    @DisplayName("an empty payload says so rather than rendering nothing at all")
    void emptyPayload() {
        assertThat(RawPayloadRenderer.hexDump(new byte[0])).isEqualTo("(0 bytes)");
    }

    @Test
    @DisplayName("a dump that hits its limit says exactly how much it withheld")
    void truncationIsDisclosed() {
        byte[] bytes = new byte[100];

        String dump = RawPayloadRenderer.hexDump(bytes, 32);

        assertThat(dump).contains("68 of 100 bytes are not shown");
        assertThat(dump).contains("limited to 32 bytes");
        assertThat(dump).contains("stored complete in the journal");
    }

    @Test
    @DisplayName("undecodable bytes produce an explanation, a failure detail, and still the bytes")
    void undecodableBinaryRecord() {
        RawPayload payload = new RawPayload(new byte[] {0x08}, SourceKind.BEP_BINARY);

        RawPayloadRenderer.Rendered rendered =
                RawPayloadRenderer.render(payload, DecodeStatus.FAILED);

        assertThat(rendered.decodeFailure()).isPresent();
        assertThat(rendered.text()).contains("could not be decoded as a BuildEvent");
        assertThat(rendered.notices()).isEmpty();
    }

    @Test
    @DisplayName("a stored status that disagrees with a fresh decode is reported, not resolved")
    void statusDisagreementIsSurfaced() {
        RawPayload payload = new RawPayload(new byte[] {0x08}, SourceKind.BEP_BINARY);

        RawPayloadRenderer.Rendered rendered =
                RawPayloadRenderer.render(payload, DecodeStatus.OK);

        assertThat(rendered.notices()).anySatisfy(notice ->
                assertThat(notice).contains("recorded this record as OK")
                        .contains("says FAILED"));
    }

    @Test
    @DisplayName("a JSON record is shown as the text it is")
    void jsonRecordIsShownVerbatim() {
        String json = "{\"id\":{\"started\":{}}}";
        RawPayload payload =
                new RawPayload(json.getBytes(StandardCharsets.UTF_8), SourceKind.BEP_JSON_RECORD);

        RawPayloadRenderer.Rendered rendered = RawPayloadRenderer.render(payload, DecodeStatus.OK);

        assertThat(rendered.text()).isEqualTo(json);
        assertThat(rendered.decodeFailure()).isEmpty();
    }

    @Test
    @DisplayName("a BES envelope says it is not decodable yet instead of showing an empty pane")
    void besEnvelopeIsExplained() {
        RawPayload payload = new RawPayload(new byte[] {1, 2, 3}, SourceKind.BES_ENVELOPE);

        RawPayloadRenderer.Rendered rendered = RawPayloadRenderer.render(payload, DecodeStatus.OK);

        assertThat(rendered.text()).contains("BES envelope").contains("Phase 2");
    }
}
