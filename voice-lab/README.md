# Voice lab

Tools to choose the voice of the staff assistant by listening and measuring, in Italian and English, all free and
local. Background: [docs/VOICE_RESEARCH.md](../docs/VOICE_RESEARCH.md). Plain Node scripts, no `npm install`
(Node 22 or newer). Every command is run from the repository root. Nothing here was run by its author: if
something breaks, the error message is the first thing to send back.

There are two kinds of engine, both small local web servers that speak the OpenAI audio API:

- **STT** (speech to text, "listening"): faster-whisper through the *speaches* server, port 8000.
- **TTS** (text to speech, "speaking"): Kokoro through *Kokoro-FastAPI*, port 8880.

## 0. Before you start

1. Start the engines (needs Docker; see the notes at the top of `docker-compose.voice.yml`, some parts are unverified):

       docker compose -f docker-compose.yml -f docker-compose.voice.yml --profile voice-gpu up -d

   No graphics card or it fails: use `--profile voice-cpu` (see the file for the two variables to set).
   The first run downloads the models, which can take several minutes.
2. Tell the scripts what is running. The first time you run any script it creates `voice-lab/candidates.json` from
   `voice-lab/candidates.example.json` and stops; open it, check the addresses, run the script again.
   This file is yours and is not committed. Each candidate is `name`, `baseUrl`, `model`; a TTS candidate also has
   `voices` (one per language) and `format`; any candidate may have `"headers": { ... }` (for a hosted engine later).
   To compare more voices, add more entries. Names may use letters, digits, dot, dash and underscore.

## 1. Listen to the voices (about 5 minutes to generate, then as long as you like to listen)

    node voice-lab/tts-compare.mjs

Speaks 24 sentences (16 Italian, 8 English) with every TTS candidate, saves the audio, times it, and builds the
listening page. Options: `--only kokoro-it-sara`, `--lang it`, `--limit 3` (try a few first). One failed call does not stop the rest.

Then:

    node voice-lab/serve.mjs

and open http://127.0.0.1:8099/out/listen.html . For each sentence you hear 1 to 4 voices under the letters A, B, C, D in
random order (you do not see which is which). Give each a score from 1 to 5 for **"sounds like a person"** and for
**"I would trust it to read out an allergy warning"**, and write a comment if you like. Keys: A B C D play, Space
play/pause, 1-5 person score, Shift+1-5 trust score, M comment, arrows previous/next. Ratings are saved in the
browser as you go. Press **Export ratings** when you finish.

Then see the result, unblinded:

    node voice-lab/score.mjs

It prints, for each candidate, the mean scores, how many you rated, and the mean time until the first sound arrives
(lower is better; under about 1 second feels instant). `--comments` also prints your comments.

## 2. Record your voice (about 20 minutes for the 60 phrases)

    node voice-lab/serve.mjs

Open http://127.0.0.1:8099/record.html (not the file from the disk: it needs the helper running, and the
microphone only works at this address). Choose the room you are in (quiet room, kitchen noise, music, outdoors),
read the phrase on screen as you would say it to the assistant, press Space to start and stop, listen,
press Enter to keep or R to redo. You can stop at any time: the next visit continues where you left off.
Record some in each room: noise is where engines differ most.

Takes are saved in `voice-lab/out/recordings/` as `<id>.wav` (16 kHz, mono) plus `<id>.json`.

## 3. Score the listening engines (about 1-3 minutes)

    node voice-lab/stt-eval.mjs

Sends every recording to every STT candidate and compares the answer with the phrase. Options: `--only`, `--lang it|en`,
`--limit N`. Prints per-phrase lines (with what was wanted when it differs) and averages per candidate, per language
and per noise note. Files: `voice-lab/out/stt/results.csv` (one row per phrase and candidate) and `summary.csv`
(open them in Excel or Sheets). Each run replaces both files.

How to read it: **WER** is the share of words that are wrong (10% means about one word in ten); **CER** is the same
for letters, so it forgives a small slip in a name. Lower is better. Before comparing, the texts are made alike
(case, accents, punctuation, "20:30" and "venti e trenta", "4" and "quattro"). That is **approximate**: phone numbers
(`stt-it-24`, `stt-en-17`) will usually look bad because engines write digits in groups; compare engines with each
other there, not with zero. Read the actual wrong words in the CSV, not only the averages: for a restaurant a wrong
surname or a missed "senza glutine" matters more than a missing "il".

## 4. Time the whole thing through the real app (about 30 seconds)

Start the app (`docker compose ... up`) with voice on, then:

    node voice-lab/latency.mjs
    node voice-lab/latency.mjs voice-lab/out/recordings/stt-it-01.wav --runs 3

Sends one recording to `/api/v1/voice/transcribe`, the text to `/api/v1/assistant/chat`, the reply to
`/api/v1/voice/speak`, five times, and prints min, median and max for each step, the time until the first sound and the total.
The target in the research notes is under 3 seconds until the first sound. Settings by environment variable:
`APP_URL` (default http://localhost:3000), `EMAIL` and `PASSWORD` (default: the demo owner of the Duodecim demo
restaurant and the demo password from the main README). The server allows 12 assistant requests a minute, so the script
starts a round at most every 5.6 seconds. It stops with a clear message if the voice endpoints answer 404 (not built
yet) or 503 (voice switched off or speech servers not reachable). The shape of the two voice requests is an assumption
written at the top of `latency.mjs`; change it there if the backend differs.

## Where things go

Everything produced is under `voice-lab/out/` (recordings, audio, results, `listen.html`), and your settings are in
`voice-lab/candidates.json`. Both are git-ignored (`voice-lab/.gitignore`; the repository `.gitignore` should also
contain `voice-lab/out/`).

## Checks

    node --test voice-lab/test/

Tests the pure parts: error rates, text clean-up, numbers to words, WAV bytes, statistics, file-name safety, the
listening page data. They do not call any speech server.

## Files

| File | What it is |
|---|---|
| `phrases.json` | 60 phrases to record, 24 sentences to speak |
| `candidates.example.json` | template for `candidates.json` |
| `serve.mjs`, `web/` | local helper: recorder page, listening page files, safe upload (127.0.0.1 only, 20 MB limit, only `recordings/` and `ratings/`) |
| `tts-compare.mjs`, `score.mjs` | speak, build the blind page, unblind the scores |
| `stt-eval.mjs` | word and character error rates |
| `latency.mjs` | full round trip through the app |
| `lib/`, `test/` | helpers and their tests |
