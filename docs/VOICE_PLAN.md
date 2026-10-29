# Voice plan: the "Jarvis" interaction

Status: plan prepared 2026-10-27 for work on 2026-10-28. Background and sources: `docs/VOICE_RESEARCH.md`. The bigger picture
(the same agent will later talk to customers on WhatsApp, Telegram and the phone): `docs/CUSTOMER_AGENT_PLAN.md`.

**Update 2026-10-29:** the engines run (speaches on the RTX 5080 does listening and speaking, Piper `paola` speaks Italian) and the phone works over Tailscale
HTTPS. The current state is in `docs/HANDOFF.md`, Block 18; sections 6 to 9 below are the original plan and are partly overtaken.

## 1. What we are building, in one paragraph
A staff member taps a microphone button (or later says "Hey Jarvis"), speaks Italian or English, and the assistant answers out loud in
a natural voice, within about three seconds. Everything it can do is what the text assistant already does (ADR 0012): read, and
*prepare* bookings that a person confirms with a button. Voice is a thin layer: audio in becomes text, the existing assistant runs
unchanged, its text reply becomes audio. **Nothing said, however it is transcribed, can confirm or execute anything.**

## 2. Facts that shape the plan (checked on AJ's PC and on the web on 2026-10-27)
| Fact | Consequence |
|------|-------------|
| GPU RTX 5080, 16 GB, driver 621.01. This is Nvidia's **Blackwell** generation (sm_120): software built before 2025 can fail with "no kernel image"; it needs builds for CUDA 12.8 or newer ([PyTorch forum](https://discuss.pytorch.org/t/pytorch-support-for-sm120/216099), [a Docker issue](https://github.com/pluja/whishper/issues/172)). | Use the `latest-cu128` Kokoro image; the speech-to-text server is the one unknown (see the ladder in section 6). |
| Docker Desktop has the `nvidia` runtime; WSL2 has Ubuntu and Kali; 315 GB free on C, 184 GB on D. | GPU containers should work. Models are big, disk is fine. |
| Ollama has `qwen3.5:9b` loaded: 5.8 GB of the 16 GB. | The speech engines must fit in about 8 GB together with Windows and anything else on the GPU. |
| **Microphones only work on secure pages** (https, or localhost) ([MDN](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia)). Today the phone uses `http://100.100.138.100:3000`: the microphone will be **blocked** there. | Desktop on `localhost` works at once. The phone needs https: Tailscale Serve. |
| Tailscale on this PC: MagicDNS is on, but **HTTPS certificates are not enabled** (`CertDomains` is empty). Enabling them publishes the machine names (here `bashi-ii`) on a public certificate log ([Tailscale](https://tailscale.com/kb/1153/enabling-https)). | One click for AJ in the admin console, and AJ must be fine with the name being public. |
| The thinking assistant takes 2 to 6 seconds to answer (thinking on: 44/50 against 29/50 off). | **The language model, not the voice, is the slow part.** See section 5. |

## 3. Architecture (kept swappable)
```
phone / browser  --https-->  Next.js (3000)  -->  backend /api/v1/voice/*  -->  STT server   (OpenAI audio API: /v1/audio/transcriptions)
   mic -> recording                                  /api/v1/assistant/chat  -->  Ollama (unchanged)
   speaker <- audio  <---------------------------    /api/v1/voice/speak     -->  TTS server   (OpenAI audio API: /v1/audio/speech)
```
- **Engines are local HTTP servers speaking the OpenAI audio API**, so any of them can be replaced by changing a URL and a model name:
  listening: faster-whisper through [speaches](https://github.com/speaches-ai/speaches) (OpenAI-compatible, GPU or CPU, supports
  streaming over server-sent events); speaking: Kokoro through [Kokoro-FastAPI](https://github.com/remsky/Kokoro-FastAPI)
  (streaming output, about 300 ms to the first audio on a GPU, about 3 to 4 GB of video memory, Italian voices `if_sara` and `im_nicola`).
  A paid or hosted engine (ElevenLabs) would be one more implementation of the same two interfaces (`SpeechRecognizer`, `SpeechSynthesizer`).
- Backend: package `voice` with `GET /status`, `POST /transcribe`, `POST /speak`, its own rate limit (a spoken turn is three calls),
  feature flag `app.voice.enabled` (off by default), no audio or text ever stored or logged.
- Browser: a microphone button in the assistant panel (tap to talk, hold to talk), a level meter, "send automatically" and "speak
  replies" switches, playback with an iPhone-safe audio unlock, everything cancellable.
- Built tonight by the dev agent, reviewed and tested by me: the backend (`backend/.../voice`), the panel (`frontend/.../assistant`,
  `e2e/tests/voice.spec.ts`), the listening and measuring tools (`voice-lab/`), and `docker-compose.voice.yml`.

## 4. Candidates, with what is known and what is not
**Listening (speech to text).** On the FLEURS test the word error rate for Italian is 2.54% for Whisper large-v3, 2.77% for
large-v3-turbo and 3.02% for NVIDIA Parakeet v3, which is about six times faster ([leaderboard](https://models.handy.computer/languages/it),
[comparison](https://wz-it.com/en/blog/local-speech-to-text-gdpr/); different benchmarks and settings, so only indicative, and
read speech in a quiet room, not a kitchen). Voxtral Mini 4B Realtime (Apache 2.0, includes Italian, truly streaming, 240 ms to 2.4 s
delay) needs a 16 GB GPU for itself ([model card](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602)): it does **not** fit next to
the 9B model now, but would if the language model were a small one. Plan: start with Whisper large-v3-turbo; Parakeet and Voxtral
are the later options.
**Speaking (text to speech).** Kokoro is tiny and fast, but its Italian voices are graded **C** by its maker (little training data):
it may sound flat. Chatterbox Multilingual (MIT, 500M parameters, Italian among 23 languages, can copy a voice from a sample) is the
natural-sounding and "Jarvis" candidate but heavier and not confirmed to stream. Piper is robotic. XTTS is not free for commercial use.
ElevenLabs sounds best, but its free plan is about 10 minutes a month with no commercial licence, and the text leaves the building.
**Wake word.** openWakeWord has a ready "hey jarvis" model, trained on synthetic speech; one user reports it firing on "hey ja"
([issue](https://github.com/dscripka/openWakeWord/issues/142)). In a browser it means an always-open microphone: fine on a wall
tablet that stays on the page, poor on a phone (iOS suspends it in the background). Later, optional.
**Voice activity detection** (knowing when you stopped talking): v1 uses a small energy detector in the browser; Silero VAD via
[vad-web](https://github.com/ricky0123/vad) is the upgrade if the simple one clips words.

## 5. Latency: where the time goes
End of speech to first sound, estimated: listening 0.3 to 1 s, **assistant 2 to 6 s**, speaking 0.3 to 0.5 s. To feel like Jarvis we need
under 3 s typical, so the work that matters is on the language-model side, in this order of cheapness:
1. Acknowledge at once ("Un attimo…", a short pre-made sound) while the model works: perceived delay drops, no accuracy lost.
2. Speak the first sentence while the rest is still being produced (needs the assistant to stream; later).
3. Keep the context small and the model loaded (already done); trim the prompt; test a smaller model on the same 53 requests (also
   the "smallest possible agent" goal and what the customer agent needs).
4. Voice-specific replies: shorter by design ("tre tavoli liberi: 2, 5 e 7"), no lists, numbers said the way people say them.
Turning thinking off is *not* an option: it made the 9B model announce actions it never took.

## 6. Tomorrow morning, in order
**Needs AJ (5 minutes, before anything else):** (a) say yes to the downloads in section 8; (b) enable HTTPS in the Tailscale admin console
(DNS page, MagicDNS must be on, "Enable HTTPS", accept that machine names go on a public log; rename the PC first if `bashi-ii` should
not be public); (c) tell me the answers to section 9.
**Step 1, bring the engines up (me, about 30 min).**
1. `docker compose -f docker-compose.yml -f docker-compose.voice.yml --profile voice-gpu up -d tts-gpu`, then one `curl` to
   `/v1/audio/speech` with an Italian sentence. This proves the Blackwell image works. If it fails with "no kernel image", use the
   CPU image for speaking (Kokoro is small enough) and carry on.
2. Same for `stt-gpu` (speaches). **Unknown:** whether its CUDA image has kernels for the 5080. Ladder if not: (a) a small custom image
   on a CUDA 12.8 base with a current faster-whisper; (b) whisper.cpp built with CUDA 12.8; (c) speaches CPU (`stt-cpu`) with
   large-v3-turbo: the Ryzen 9800X3D should manage a few seconds per sentence, enough to evaluate with.
3. Check the memory: `nvidia-smi` with Ollama, STT and TTS loaded, and with a game open if AJ wants to play while the system runs.
**Step 2, choose the voices and the listener (AJ, about 90 min, with `voice-lab/`).** See section 7, tests T1 and T2. This is the part that
cannot be automated: only AJ's ears can say which voice is acceptable.
**Step 3, wire it and try it on the desktop (me then AJ, about 60 min).** `VOICE_ENABLED=true`, rebuild, open `http://localhost:3000`,
tap the microphone. Run the ten spoken tasks (T3). Measure with `node voice-lab/latency.mjs`.
**Step 4, the phone over https (AJ, about 30 min).** On the PC: `tailscale serve --bg 3000` (confirm the exact syntax with
`tailscale serve --help`; I will not change Tailscale or Windows settings myself), then open `https://bashi-ii.tail9b1c54.ts.net` on the
iPhone (name may change if renamed). Repeat T3 and T4. Then set `COOKIE_SECURE=true`.
**Step 5, tune (me, the rest of the day):** whatever the tests show: text normalisation for times and phone numbers, the acknowledge sound,
the spoken style of the replies, a first look at a smaller model for latency.

## 7. What AJ tests (and how we judge it)
| # | Test | Time | Pass when |
|---|------|------|-----------|
| T1 | **Blind listening**: 24 sentences x 2 to 3 voices in `out/listen.html`, rate "sounds like a person" and "I'd trust it for an allergy warning", 1 to 5 | 30 min | a voice averages **4 or more** on both, and says numbers, times and surnames correctly |
| T2 | **Recognition**: read the 60 phrases into `record.html` in a quiet room, then 20 of them with music or a video of a kitchen playing | 40 min | word error **8% or less** quiet, **15% or less** noisy, and surnames, numbers and allergy words are right |
| T3 | **Ten spoken tasks** with the real assistant on the desktop (who booked tonight, free tables at lunch, book Rossi for 4 on Sunday at 21, cancel Bianchi, kitchen queue, price of a margherita, a vague request, a request it cannot do, "yes confirm" said aloud (must do nothing), a nut-allergy question) | 15 min | at least 8/10 right, none unsafe, **"yes confirm" never confirms** |
| T4 | **Phone**, same ten tasks, Wi-Fi and mobile data, over https | 30 min | works like the desktop; latency within 1 s of it |
| T5 | **Hard conditions**: interrupt it while it speaks; a long pause; whisper; speaking fast; a second person talking; Italian with an English dish name | 20 min | no crash, no stuck microphone, no unintended action |
| T6 | **Feel**: end of your sentence to first sound, 20 times (`node voice-lab/latency.mjs`) | 10 min | median **3 s or less**; if more, the notes say what dominates |
Write notes as you go (what you said, what it did, what annoyed you): a messy list is fine, I turn it into fixes.

## 8. Downloads that need AJ's yes (sizes are estimates; I could not measure them from the registry)
| What | From | Size (rough) |
|------|------|--------------|
| Kokoro-FastAPI GPU image `ghcr.io/remsky/kokoro-fastapi-gpu:latest-cu128` (models are inside the image) | GitHub container registry | several GB |
| speaches image `ghcr.io/speaches-ai/speaches:latest-cuda` and, if needed, `latest-cpu` | GitHub container registry | several GB each |
| Whisper large-v3-turbo model (fetched by speaches on first use) | Hugging Face | about 1.6 GB |
| Optional later: Chatterbox Multilingual | Hugging Face | a few GB |
If AJ says yes tonight I can pull the images in the background so tomorrow starts with servers already running.

## 9. Decisions AJ makes tomorrow
1. **Persona and voice:** male or female; in English a British butler tone (Kokoro `bm_george` or `bm_lewis`) or neutral; in Italian
   `im_nicola` or `if_sara` or a better one found in T1. A cloned "movie Jarvis" voice needs consent and a clean licence: not now.
2. **Replies spoken by default?** (My suggestion: off until the person turns it on; on for a wall tablet.)
3. **Wake word** ("Hey Jarvis") yes or no, and on which device (a wall or bar tablet is the sensible place).
4. **Spoken confirmation** ("sì, confermo"): my recommendation is **no**: in a restaurant someone else's voice, the TV or a guest at
   the next table can say it. If wanted later: only with a second factor (a PIN tapped, or a hardware button) and only for low-risk actions.
5. **Which device** will really use voice in the pilot: personal phones, or one shared tablet?
6. The Tailscale https decision (public machine name).

## 10. Risks and how we limit them
| Risk | Limit |
|------|-------|
| Speech-to-text server does not run on the 5080 | the CPU fallback and the ladder in step 1; we can still judge everything else |
| GPU memory runs out (9B model + STT + TTS + a game) | measure in step 1; unload the TTS (`/dev/unload`) or move STT to the CPU |
| Noisy restaurant: wrong words | test T2 with noise; a visible transcript on screen so a mistake is seen before it is acted on; confirm stays a button |
| Heard wrongly: "cancel Rossi" becomes "cancel Bruni" | the card shows the exact booking and date; Confirm is a tap |
| Someone else's voice triggers an action | voice can only *ask*; nothing executes without the button; no spoken confirmation |
| Italian voice sounds robotic | blind test decides; Chatterbox next; ElevenLabs as the paid reference |
| Latency feels slow | section 5 |
| iPhone audio rules (autoplay, background) | playback is unlocked by the first tap on the microphone; the page must stay open |
| Privacy | audio never stored, nothing logged, local engines only, the voice button visible when the microphone is on |
Rollback is one setting: `VOICE_ENABLED=false`.

## 11. Not in scope now
Wake word, spoken confirmation, voice cloning, streaming recognition (Voxtral), the customer channels and the phone: see
`docs/CUSTOMER_AGENT_PLAN.md`. The voice skeleton is built so none of them needs a rewrite: the engines are behind two interfaces, and
the assistant behind the principal seam described there.
