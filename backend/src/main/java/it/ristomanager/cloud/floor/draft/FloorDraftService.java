package it.ristomanager.cloud.floor.draft;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.AiException.Kind;
import it.ristomanager.cloud.ai.ChatMessage;
import it.ristomanager.cloud.ai.ChatOptions;
import it.ristomanager.cloud.ai.LlmClient;
import it.ristomanager.cloud.ai.LlmReply;
import it.ristomanager.cloud.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proposes a floor plan from a photo or sketch of a room. Reads nothing from and writes nothing to the database:
 * the result is only a draft for the owner to review.
 */
@Service
public class FloorDraftService {

    private static final String BAD_IMAGE = "Use a JPEG or PNG image";
    private static final int MAX_SIDE_PX = 8000;
    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final LlmClient llm;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public FloorDraftService(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * @throws ApiException when the bytes are not a JPEG or PNG of a sensible size
     * @throws AiException when the model cannot be used or answers with something unusable
     * @throws IllegalArgumentException with the message "room not found" when the answer holds no usable room
     */
    public FloorDraft draft(byte[] image, double roomWidthMeters) {
        if (!isJpeg(image) && !isPng(image)) {
            throw ApiException.badRequest(BAD_IMAGE);
        }
        int[] size = pixelSize(image);
        if (size == null) {
            throw ApiException.badRequest(BAD_IMAGE);
        }
        if (size[0] > MAX_SIDE_PX || size[1] > MAX_SIDE_PX) {
            throw ApiException.badRequest("The image is too large: at most " + MAX_SIDE_PX + " pixels on each side");
        }

        LlmReply reply = llm.chat(
                List.of(ChatMessage.system(FloorDraftPrompt.SYSTEM),
                        ChatMessage.userWithImages(FloorDraftPrompt.userPrompt(roomWidthMeters),
                                List.of(Base64.getEncoder().encodeToString(image)))),
                new ChatOptions(FloorDraftSchema.schema(), false, TIMEOUT));

        JsonNode answer;
        try {
            answer = mapper.readTree(reply.content());
        } catch (JacksonException e) {
            throw new AiException(Kind.BAD_RESPONSE, "The model's answer is not valid JSON");
        }
        if (answer == null || !answer.path("room").isObject()) {
            throw new AiException(Kind.BAD_RESPONSE, "The model's answer has no room");
        }
        return FloorDraftMapper.toDraft(answer, size[0], size[1], roomWidthMeters);
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] b) {
        return b.length > 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    /**
     * Width and height in pixels, or null when the bytes are not a readable image. Only the header is read, so an
     * image that is small to upload but enormous to decode cannot exhaust memory here.
     */
    private static int[] pixelSize(byte[] image) {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 ? new int[] {width, height} : null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
