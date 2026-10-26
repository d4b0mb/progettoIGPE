package it.ristomanager.cloud.floor.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.AiException.Kind;
import it.ristomanager.cloud.ai.ChatMessage;
import it.ristomanager.cloud.ai.ChatOptions;
import it.ristomanager.cloud.ai.LlmClient;
import it.ristomanager.cloud.ai.LlmReply;
import it.ristomanager.cloud.common.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FloorDraftServiceTest {

    private static final String GOOD_ANSWER = """
            {"room":{"left":0.1,"top":0.1,"right":0.9,"bottom":0.9},
             "tables":[{"shape":"ROUND","cx":0.5,"cy":0.5,"width":0.1,"height":0.1,"rotation":0}],
             "elements":[],"notes":""}""";

    /** Records what it was asked and answers with a fixed text. */
    private static final class FakeLlm implements LlmClient {
        final List<List<ChatMessage>> messages = new ArrayList<>();
        final List<ChatOptions> options = new ArrayList<>();
        final String answer;

        FakeLlm(String answer) {
            this.answer = answer;
        }

        @Override
        public LlmReply chat(List<ChatMessage> messages, ChatOptions options) {
            this.messages.add(messages);
            this.options.add(options);
            return new LlmReply(answer, "fake", 0, 0, 0);
        }

        @Override
        public boolean isReachable() {
            return true;
        }

        @Override
        public String modelName() {
            return "fake";
        }
    }

    @Test
    void aPngIsAcceptedAndSentToTheModelOnce() throws Exception {
        byte[] png = image("png", 400, 300);
        FakeLlm llm = new FakeLlm(GOOD_ANSWER);

        new FloorDraftService(llm).draft(png, 16);

        assertSentOnce(llm, png);
    }

    @Test
    void aJpegIsAcceptedAndSentToTheModelOnce() throws Exception {
        byte[] jpeg = image("jpg", 400, 300);
        FakeLlm llm = new FakeLlm(GOOD_ANSWER);

        new FloorDraftService(llm).draft(jpeg, 16);

        assertSentOnce(llm, jpeg);
    }

    @Test
    void theAnswerIsMappedToADraftInMetres() throws Exception {
        FloorDraft draft = new FloorDraftService(new FakeLlm(GOOD_ANSWER)).draft(image("png", 400, 300), 16);

        assertThat(draft.widthMeters()).isEqualTo(16.0);
        assertThat(draft.heightMeters()).isEqualTo(12.0);
        assertThat(draft.tables()).hasSize(1);
        assertThat(draft.tables().get(0).shape()).isEqualTo("ROUND");
        assertThat(draft.tables().get(0).xMeters()).isEqualTo(8.0);
        assertThat(draft.tables().get(0).yMeters()).isEqualTo(6.0);
        assertThat(draft.warnings()).isEmpty();
    }

    @Test
    void bytesThatAreNotAnImageAreABadRequestAndNeverReachTheModel() {
        FakeLlm llm = new FakeLlm(GOOD_ANSWER);
        FloorDraftService service = new FloorDraftService(llm);

        assertBadImage(service, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertBadImage(service, new byte[0]);
        assertThat(llm.messages).isEmpty();
    }

    @Test
    void aFileThatOnlyStartsLikeAPngIsABadRequest() {
        assertBadImage(new FloorDraftService(new FakeLlm(GOOD_ANSWER)),
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 0, 0});
    }

    @Test
    void anImageOverEightThousandPixelsIsABadRequest() throws Exception {
        FakeLlm llm = new FakeLlm(GOOD_ANSWER);

        assertThatThrownBy(() -> new FloorDraftService(llm).draft(image("png", 8001, 1), 16))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(llm.messages).isEmpty();
    }

    @Test
    void invalidJsonFromTheModelIsABadResponseThatDoesNotEchoTheText() throws Exception {
        byte[] png = image("png", 400, 300);

        assertThatThrownBy(() -> new FloorDraftService(new FakeLlm("this is not json")).draft(png, 16))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE);
                    assertThat(e.getMessage()).doesNotContain("this is not json");
                });
    }

    @Test
    void anAnswerWithoutARoomIsABadResponse() throws Exception {
        byte[] png = image("png", 400, 300);

        assertThatThrownBy(() -> new FloorDraftService(new FakeLlm("{\"tables\":[]}")).draft(png, 16))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
    }

    @Test
    void aRoomThatCannotBeUsedIsReportedAsRoomNotFound() throws Exception {
        byte[] png = image("png", 400, 300);
        String inverted = "{\"room\":{\"left\":0.9,\"top\":0.1,\"right\":0.1,\"bottom\":0.9},\"tables\":[],\"elements\":[],\"notes\":\"\"}";

        assertThatThrownBy(() -> new FloorDraftService(new FakeLlm(inverted)).draft(png, 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("room not found");
    }

    private static void assertSentOnce(FakeLlm llm, byte[] sent) {
        assertThat(llm.messages).hasSize(1);
        List<ChatMessage> messages = llm.messages.get(0);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).images()).isEmpty();
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).images()).containsExactly(Base64.getEncoder().encodeToString(sent));
        ChatOptions options = llm.options.get(0);
        assertThat(options.format()).isNotNull();
        assertThat(options.think()).isFalse();
        assertThat(options.timeout()).isNotNull();
    }

    private static void assertBadImage(FloorDraftService service, byte[] bytes) {
        assertThatThrownBy(() -> service.draft(bytes, 16))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Use a JPEG or PNG image");
                });
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
