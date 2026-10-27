package it.ristomanager.cloud.support;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.SpeechAudio;
import it.ristomanager.cloud.ai.SpeechRecognizer;
import it.ristomanager.cloud.ai.SpeechSynthesizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Speech engines the test controls, in one object: it listens and speaks with whatever the test queued (a result, or an
 * {@link AiException} to throw) and remembers what it was asked. Integration tests use it in place of the real engines
 * through {@link ScriptedSpeechConfig}.
 *
 * Both interfaces have an {@code isAvailable()}; as a single method here it is true only when listening and speaking
 * are both available. The application sees the two separate views ({@link #asRecognizer()}, {@link #asSynthesizer()}),
 * each with its own flag.
 */
public class ScriptedSpeech implements SpeechRecognizer, SpeechSynthesizer {

    /** One call to {@code transcribe}. */
    public record Heard(byte[] audio, String mimeType, String languageTag) {
    }

    /** One call to {@code speak}. */
    public record Spoken(String text, String languageTag, String voice) {
    }

    /** Results the test queued for {@code transcribe}: a String (the transcript) or an {@link AiException} to throw. */
    public final Deque<Object> transcripts = new ArrayDeque<>();
    /** Results the test queued for {@code speak}: a {@link SpeechAudio} or an {@link AiException} to throw. */
    public final Deque<Object> speech = new ArrayDeque<>();
    public final List<Heard> heard = new ArrayList<>();
    public final List<Spoken> spoken = new ArrayList<>();
    /** What the listening engine's {@code isAvailable()} answers. */
    public volatile boolean listeningAvailable = true;
    /** What the speaking engine's {@code isAvailable()} answers. */
    public volatile boolean speakingAvailable = true;

    @Override
    public synchronized String transcribe(byte[] audio, String audioMimeType, String languageTag) {
        heard.add(new Heard(audio.clone(), audioMimeType, languageTag));
        Object next = transcripts.poll();
        if (next == null) {
            throw new IllegalStateException("the test queued no more transcripts");
        }
        if (next instanceof AiException e) {
            throw e;
        }
        return (String) next;
    }

    @Override
    public synchronized SpeechAudio speak(String text, String languageTag, String voice) {
        spoken.add(new Spoken(text, languageTag, voice));
        Object next = speech.poll();
        if (next == null) {
            throw new IllegalStateException("the test queued no more speech");
        }
        if (next instanceof AiException e) {
            throw e;
        }
        return (SpeechAudio) next;
    }

    /** True only when both halves are available. */
    @Override
    public boolean isAvailable() {
        return listeningAvailable && speakingAvailable;
    }

    /** This object as the listening engine alone, answering {@code isAvailable()} from {@link #listeningAvailable}. */
    public SpeechRecognizer asRecognizer() {
        return new SpeechRecognizer() {
            @Override
            public String transcribe(byte[] audio, String audioMimeType, String languageTag) {
                return ScriptedSpeech.this.transcribe(audio, audioMimeType, languageTag);
            }

            @Override
            public boolean isAvailable() {
                return listeningAvailable;
            }
        };
    }

    /** This object as the speaking engine alone, answering {@code isAvailable()} from {@link #speakingAvailable}. */
    public SpeechSynthesizer asSynthesizer() {
        return new SpeechSynthesizer() {
            @Override
            public SpeechAudio speak(String text, String languageTag, String voice) {
                return ScriptedSpeech.this.speak(text, languageTag, voice);
            }

            @Override
            public boolean isAvailable() {
                return speakingAvailable;
            }
        };
    }

    /** Forgets everything between tests (the Spring context, and so this object, is shared by the test classes). */
    public synchronized void reset() {
        transcripts.clear();
        speech.clear();
        heard.clear();
        spoken.clear();
        listeningAvailable = true;
        speakingAvailable = true;
    }

    /** How many times {@code transcribe} was called since the last reset. */
    public synchronized int heardCalls() {
        return heard.size();
    }

    /** How many times {@code speak} was called since the last reset. */
    public synchronized int spokenCalls() {
        return spoken.size();
    }
}
