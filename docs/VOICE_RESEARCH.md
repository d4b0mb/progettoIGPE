# Voice for the assistant: research notes (2026-10-26)

Goal: the staff assistant can listen and speak naturally, for free, in Italian and English. The backend already has two
empty slots for it: `SpeechRecognizer` (audio in, text out) and `SpeechSynthesizer` (text in, audio out). Nothing below is built.
Most sources are blogs and vendor pages: these are leads to test on our own Italian audio, not measured facts.

## Listening (speech to text)
- **Whisper large-v3-turbo through faster-whisper**: free, runs on the GPU, Italian is a well-supported language. One benchmark
  (FLEURS, a different GPU) gave about 2.5-2.8% word error for Italian. Needs a small local service next to Ollama.
- Test on real recordings of noisy-room waiter speech before trusting any number.

## Speaking (text to speech)
| Option | Cost / licence | Italian | Notes |
|---|---|---|---|
| **Kokoro-82M** | free, Apache-2.0 | Yes: voices `if_sara` (F) and `im_nicola` (M), both graded C by the maker (little training data) | Tiny, fast, runs on CPU or GPU. Best first candidate; quality in Italian must be heard. |
| **Chatterbox Multilingual** | free, MIT | Yes, among 23 languages | Larger (500M), can clone a voice from a short sample: the "Jarvis" route. Streaming and VRAM use not confirmed. |
| Piper | free, GPL-3 | Has Italian voices | Fast but more robotic. Its main repository was archived in Oct 2025. |
| XTTS v2 | non-commercial licence | Yes | Ruled out if this is ever sold. |
| **ElevenLabs** | free plan about 10,000 credits a month (about 10 minutes of speech), **no commercial licence**; cheapest paid plan about 6 dollars a month | Yes, very natural | Cloud: text leaves the restaurant. Fine for demos, not free for a real product. |

## Speech-to-speech
Open-source stacks exist that chain voice detection, Whisper, an Ollama model and Kokoro or Piper (for example the "Maya"
project). They are the same three parts we already have slots for, so no single audio-in audio-out model is needed to start.

## Proposed plan (free)
1. Run faster-whisper and Kokoro as small local services (a Docker container or a Python process on the PC).
2. Implement `SpeechRecognizer` and `SpeechSynthesizer` as HTTP clients to them, same pattern as `OllamaClient`.
3. Record 20 Italian and English phrases, listen to Kokoro and Chatterbox side by side, and pick by ear.
4. Keep ElevenLabs as an optional paid-later provider behind the same interface.
5. Latency target: under 3 seconds from end of speech to start of the spoken answer. Streaming the text-to-speech sentence by sentence is the usual way to get there.

## Open questions
- Is Kokoro's grade-C Italian good enough? Needs an ear test.
- Does Chatterbox Multilingual run alongside the 9b model in 16 GB of video memory? Needs a measurement.
- A "Jarvis" voice cloned from a person's recording has consent and licence questions: use a voice we have the right to use.
