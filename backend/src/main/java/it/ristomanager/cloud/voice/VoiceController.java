package it.ristomanager.cloud.voice;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.SpeechAudio;
import it.ristomanager.cloud.ai.SpeechRecognizer;
import it.ristomanager.cloud.ai.SpeechSynthesizer;
import it.ristomanager.cloud.assistant.AiGate;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.security.CurrentUser;
import it.ristomanager.cloud.voice.VoiceTypes.SpeakRequest;
import it.ristomanager.cloud.voice.VoiceTypes.TranscribeResult;
import it.ristomanager.cloud.voice.VoiceTypes.VoiceStatus;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Speech in and out for the staff assistant, as a thin layer around text: audio to text, and text to audio. It never
 * talks to the assistant and never confirms or runs anything; what the person says only becomes text they can read, and
 * only the assistant's own text is spoken. Nothing is stored and no content is logged.
 *
 * Every call goes through the voice gate (its own limits), so a spoken turn (listen, think, speak) does not use up the
 * assistant's budget.
 */
@RestController
@RequestMapping("/api/v1/voice")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','WAITER','KITCHEN')")
class VoiceController {

    private static final Set<String> LANGUAGES = Set.of("it", "en");
    private static final Set<String> AUDIO_TYPES = Set.of(
            "audio/webm", "audio/ogg", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac",
            "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3");
    private static final Pattern VOICE_NAME = Pattern.compile("^[A-Za-z0-9_\\-]{1,40}$");
    private static final int MAX_TRANSCRIPT_CHARS = 1000;

    private final SpeechRecognizer recognizer;
    private final SpeechSynthesizer synthesizer;
    private final VoiceProperties props;
    private final AiGate gate;

    VoiceController(SpeechRecognizer recognizer, SpeechSynthesizer synthesizer, VoiceProperties props,
                    @Qualifier("voiceGate") AiGate gate) {
        this.recognizer = recognizer;
        this.synthesizer = synthesizer;
        this.props = props;
        this.gate = gate;
    }

    /** Whether voice is on and each engine is answering. The two probes run side by side, so this takes 2 seconds at most. */
    @GetMapping("/status")
    VoiceStatus status() {
        if (!props.enabled()) {
            return new VoiceStatus(false, false, false);
        }
        CompletableFuture<Boolean> listening = CompletableFuture.supplyAsync(recognizer::isAvailable);
        CompletableFuture<Boolean> speaking = CompletableFuture.supplyAsync(synthesizer::isAvailable);
        return new VoiceStatus(true, listening.exceptionally(e -> false).join(), speaking.exceptionally(e -> false).join());
    }

    /** Audio in, text out. The text is only returned to the caller: it is not sent to the assistant from here. */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TranscribeResult transcribe(@AuthenticationPrincipal Jwt jwt, @RequestPart("audio") MultipartFile audio,
                                @RequestParam(value = "language", required = false) String language) {
        if (language != null && !LANGUAGES.contains(language)) {
            throw ApiException.invalid("language", "language must be it or en");
        }
        if (audio.isEmpty()) {
            throw ApiException.invalid("audio", "Record something first");
        }
        if (audio.getSize() > props.limits().maxAudioBytes()) {
            throw new ApiException(HttpStatus.valueOf(413), "The recording is too long");
        }
        String type = VoiceHttp.baseMimeType(audio.getContentType());
        if (type == null || !AUDIO_TYPES.contains(type)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported audio format");
        }
        byte[] read;
        try {
            read = audio.getBytes();
        } catch (IOException e) {
            throw ApiException.invalid("audio", "The recording could not be read");
        }
        final byte[] bytes = read;
        UUID userId = CurrentUser.from(jwt).userId();
        try {
            String heard = gate.run(userId, () -> recognizer.transcribe(bytes, type, language));
            return new TranscribeResult(VoiceText.clean(heard, MAX_TRANSCRIPT_CHARS));
        } catch (AiException e) {
            throw VoiceErrors.toApi(e);
        }
    }

    /** Text in, audio out. The client sends short text (a sentence or two); longer text is refused, not cut. */
    @PostMapping(value = "/speak", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "The spoken audio, in the format the engine produced",
            content = @Content(mediaType = "audio/*", schema = @Schema(type = "string", format = "binary")))
    ResponseEntity<byte[]> speak(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SpeakRequest request) {
        String text = VoiceText.normalise(request.text());
        if (text.isEmpty()) {
            throw ApiException.invalid("text", "There is nothing to say");
        }
        if (text.length() > props.limits().maxTextChars()) {
            throw ApiException.invalid("text", "The text is too long to speak");
        }
        String language = request.language();
        if (language != null && !LANGUAGES.contains(language)) {
            throw ApiException.invalid("language", "language must be it or en");
        }
        String voice = request.voice();
        if (voice != null && !VOICE_NAME.matcher(voice).matches()) {
            throw ApiException.invalid("voice", "voice is not valid");
        }
        UUID userId = CurrentUser.from(jwt).userId();
        SpeechAudio spoken;
        try {
            spoken = gate.run(userId, () -> synthesizer.speak(text, language, voice));
        } catch (AiException e) {
            throw VoiceErrors.toApi(e);
        }
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(spoken.mimeType());
        } catch (InvalidMediaTypeException e) {
            throw VoiceErrors.toApi(new AiException(AiException.Kind.BAD_RESPONSE, "Speech engine sent no usable type"));
        }
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noStore())
                .body(spoken.bytes());
    }
}
