package it.ristomanager.cloud.voice;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the voice layer (listening and speaking). Off by default. Both engines are local HTTP servers that speak
 * the OpenAI audio API, so they can be swapped by changing a URL and a model name.
 *
 * @param enabled whether voice is on; when off every voice call is refused and the status endpoint says so
 * @param stt     the listening engine (speech to text)
 * @param tts     the speaking engine (text to speech)
 * @param limits  size and rate limits of the voice endpoints
 */
@ConfigurationProperties("app.voice")
public record VoiceProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue Stt stt,
        @DefaultValue Tts tts,
        @DefaultValue Limits limits) {

    /**
     * @param baseUrl  address of the OpenAI-compatible speech-to-text server
     * @param model    model name the server expects
     * @param language "it" or "en" to force a language; null or blank lets the engine detect it
     * @param timeout  how long one transcription may take
     */
    public record Stt(
            @DefaultValue("http://localhost:8000") String baseUrl,
            @DefaultValue("whisper-1") String model,
            String language,
            @DefaultValue("30s") Duration timeout) {
    }

    /**
     * @param baseUrl address of the OpenAI-compatible text-to-speech server
     * @param model   model name the server expects
     * @param voiceIt default voice for Italian
     * @param voiceEn default voice for every other language
     * @param format  audio format asked for (mp3, wav, opus, aac, flac)
     * @param timeout how long one synthesis may take
     */
    public record Tts(
            @DefaultValue("http://localhost:8880") String baseUrl,
            @DefaultValue("kokoro") String model,
            @DefaultValue("if_sara") String voiceIt,
            @DefaultValue("af_heart") String voiceEn,
            @DefaultValue("mp3") String format,
            @DefaultValue("30s") Duration timeout) {
    }

    /**
     * @param maxAudioBytes   largest recording accepted for transcription
     * @param maxTextChars    longest text accepted for speaking (the client sends short sentences)
     * @param maxConcurrent   voice calls running at the same time, across all users
     * @param perUserPerMinute voice calls one person may start in any 60 seconds
     */
    public record Limits(
            @DefaultValue("5242880") long maxAudioBytes,
            @DefaultValue("600") int maxTextChars,
            @DefaultValue("2") int maxConcurrent,
            @DefaultValue("40") int perUserPerMinute) {
    }
}
