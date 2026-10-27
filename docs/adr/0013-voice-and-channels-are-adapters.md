# 0013. Voice and message channels are adapters around one assistant

Status: Proposed (2026-10-27), to be accepted when the first voice test passes.

## Situation
The assistant (ADR 0012) is text only. We want to talk to it, and later the same agent must talk to customers on WhatsApp,
Telegram and the phone ("facing both ways": staff inside, strangers outside). Speech engines are local, change quickly, and the
best Italian voice is not known yet.

## Decision
1. **Voice is a thin text layer.** Audio becomes text, the existing assistant runs unchanged, its text reply becomes audio. The voice
   code has no business logic and no tools. The rule of ADR 0012 stays: nothing said, however transcribed, confirms or executes
   anything; a person presses Confirm.
2. **Engines sit behind two small interfaces** (`SpeechRecognizer`, `SpeechSynthesizer`) with one implementation for any local server
   that speaks the OpenAI audio API. Swapping an engine is a URL and a model name; a hosted engine is another implementation.
3. **Everything is off by default** (`app.voice.enabled=false`), has its own rate limit (one spoken turn is three calls), stores no
   audio and logs no audio or text.
4. **Channels are adapters, and the principal decides the powers.** Every conversation, whatever the channel, is bound to a principal:
   STAFF(role) or GUEST(channel, verified identity). Tools are chosen from the principal by code (the mechanism that already separates
   waiter from manager). A guest principal gets a very small tool set, own data only, and can only create requests that staff
   approve. Details and the order of work: `docs/CUSTOMER_AGENT_PLAN.md`.
5. **Third-party agent platforms are transports at most.** OpenClaw and similar hold powerful tools and have a poor security record;
   if used, they only carry text to one narrow endpoint of our own service, which owns the principal, tools, checks and audit.

## Consequences
- The first voice work needs no change to the assistant's core, and the customer work can start by adding a principal, not by rewriting.
- Latency is dominated by the language model, not the speech engines: work on acknowledgement sounds, streaming the first sentence and
  smaller models matters more than a faster voice.
- Microphones need https: phones need Tailscale Serve (or another https front) before voice works there.

## Rejected
- *Speech-to-speech model as the assistant*: one opaque model that listens and answers in audio cannot be checked by our code
  (no claim checks, no confirmation gate).
- *A separate customer bot with its own code*: two agents drift apart; shared facts (menu, hours) and shared safety checks must live
  in one place.
- *Giving a general agent platform direct access to restaurant data*: a stranger's message must never be able to reach tools.
