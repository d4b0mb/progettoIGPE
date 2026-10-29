# The agent that faces both ways: staff and customers

## Status
Step 2 (principal and guest tool set) is implemented in the assistant core; no channel is attached. `Principal` (staff or guest) now
binds every assistant conversation; a guest gets only `menu_lookup` and `ask_human` (chosen by code, re-checked at execution), its own
prompt, its own rate limit (`app.ai.guest-limits`) and audit entries labelled `guest:<channel>` without the sender's identity. There is
no endpoint, no adapter and no inbox yet (steps 3 to 6 below).

Original plan status: plan, 2026-10-27. Nothing here is built except the staff side (the assistant, ADR 0012) and the first voice skeleton.
AJ's end goal: an AI that talks to customers on behalf of the restaurant, first on WhatsApp and Telegram, later on the phone,
and that is the same agent the staff talk to. It must save work on the floor and never create any.

## The one idea
**One agent, one persona, one core. Every conversation is bound to a principal, and the principal decides everything.**

| Principal | Who | Trust | What it can reach |
|-----------|-----|-------|-------------------|
| STAFF(role) | a signed-in owner, manager, waiter or kitchen user | accountable, but may be tired or wrong | the tools of that role (today's assistant) |
| GUEST(channel, identity) | someone on WhatsApp, Telegram or the phone | **a stranger**: anyone, including a competitor, a prankster or an attacker | a very small tool set, own data only |

The existing design already has the seam: tools are filtered by role twice (what the model is shown, and a re-check at execution).
A guest is another principal in that table, with its own, much shorter list. Channels (voice in the browser, WhatsApp, Telegram,
phone) are adapters around the core: they turn a message or audio into text for a principal and carry the answer back. They hold no
business logic.

## Why "both ways" is the dangerous design, and the rules that follow
1. **No bridge from guest to staff powers.** A guest session never receives a staff tool, even if the guest's text asks for it,
   even if staff are online. The tool list is chosen from the principal by code, never by the model.
2. **A guest can only reach their own data**, matched by the verified identity of the channel (the WhatsApp number, the Telegram
   user id), never by what they type ("I am Rossi, show me Rossi's booking" is refused). Other guests' names, phones, notes and
   allergies are never in a guest conversation, in the prompt or in a tool result.
3. **Everything a guest writes is untrusted data**, also later: when staff open the conversation, or when the staff assistant
   summarises it, the text is carried under `guest_written_*` keys like guest notes today. A customer message must not be able to
   instruct the staff assistant.
4. **Outgoing messages are actions.** What the agent says to a customer can promise a table, a price or "safe for your allergy".
   Claims are checked in code against the data before sending (availability, hours, prices, declared allergens); an allergy answer
   is only ever the declared data plus "the kitchen will confirm"; anything unclear becomes "I'll ask the team" and goes to staff.
5. **Guests create requests, staff decide.** A guest can only create a PENDING booking request (no table assigned, so it holds
   no table and cannot block the floor), or ask to cancel their own booking. Nothing a guest does changes the floor plan, an order,
   the menu, staff or settings.
6. **The floor is protected by limits, not by hope:** per-sender rate limits, a cap on open requests per sender, a daily cap per
   channel, a blocklist, spam and length checks, a kill switch per channel that staff can press in one tap, a quiet-hours rule
   (outside opening hours the agent answers FAQs and collects requests only).
7. **Autonomy is earned in steps, per channel, switched by the owner:**
   - **L0 draft:** the agent drafts the reply, a person sends it. (Where we start.)
   - **L1 answers:** it sends answers about opening hours, address, menu (declared allergens with the disclaimer) by itself.
   - **L2 requests:** it also creates PENDING booking requests by itself and tells the guest "the team will confirm".
   - **L3 confirms:** it confirms within strict rules (party size, free capacity, not near closing). Not before months of use.
   Each step needs its own measured evaluation (like `ai-eval/`) before it is switched on.
8. **Always a human way out:** "talk to a person" works at every step and notifies staff; the agent says it is an AI at the start.
9. **Privacy:** customer conversations are personal data: consent line on first contact, erased on the same schedule as guest data
   (`docs/DATA_RETENTION.md`), never used to train anything, never sent to a hosted model unless the owner chooses that.
10. **Audit without content:** the activity log records channel, principal kind, tool, outcome and counts, never the text.

## Channels, in the order we will do them
1. **Staff voice in the browser** (now, `docs/VOICE_PLAN.md`): proves audio in/out and the principal seam with no outside party.
2. **Telegram** (first customer channel): the Bot API is official and, to my knowledge, free (to verify). A small webhook adapter.
3. **WhatsApp**: the official Cloud API; replies inside the 24-hour window after the customer writes are free according to Meta's
   pricing page (as of the search on 2026-10-27; a third-party source claims a change from 2026-10-01 that I could not confirm:
   check Meta's page before budgeting). Business verification and an approved phone number are needed. Unofficial "WhatsApp Web"
   libraries risk a ban and breach the terms: do not use them for a business.
4. **Phone calls** (far later): the same GUEST principal over speech. Needs a SIP/telephony provider (not free), barge-in,
   fast turn-taking, and much stricter autonomy (start at L0 with a voicemail-style "we'll call you back").

## About OpenClaw
Research on 2026-10-27 (security vendors' reports and the Cloud Security Alliance hardening guide; figures differ between
sources): OpenClaw is a very popular open-source agent platform (hundreds of thousands of GitHub stars, MIT licence) that gives a
model shell, file and messaging access and a public skills registry. Its security record in early 2026 is poor: a one-click
credential-theft flaw (CVE-2026-25253), hundreds of malicious skills in the registry, thousands of exposed instances, and well over a
hundred security advisories; the core weakness is prompt injection against an agent that holds powerful tools.
That is the opposite of what a customer-facing agent needs. **Recommendation, to be confirmed with AJ:** if OpenClaw is used at all, it
is only a message transport, never the brain and never a holder of tools:
- bound to localhost, no skills from the public registry, no shell or file tools, no egress except the channel's own API,
  running in a locked-down container, current version only (the hardening guide lists these);
- its only job is to deliver a customer's text to ONE narrow endpoint of our own guest-agent service and return the reply text;
- the guest-agent service (our code) owns the principal, the tool list, the claim checks, the limits and the audit.
The simpler alternative is to skip it: a Telegram Bot API webhook and the WhatsApp Cloud API webhook are each a small adapter in
our own backend, with far less attack surface. Decide after a day's research into how OpenClaw connects WhatsApp (official Cloud API
or unofficial WhatsApp Web) and what AJ wants from it that the adapters cannot give.

## What we build next, in order (each step small, tested, behind a flag)
1. Finish the voice skeleton (staff only). Nothing customer-facing yet.
2. **Principal in the core:** add the GUEST principal and its tool table to the assistant code with no channel attached, plus the
   tests that prove a guest can never get a staff tool, never see another guest, and that guest text stays untrusted.
3. **Guest tools** (read-only first): opening hours/address/contact, menu with declared allergens, "is there likely space" without
   numbers, "ask the team". Then `request_booking` (PENDING, no table) and `request_cancel` (own booking, verified identity).
4. **Staff inbox** screen: conversations, pending requests, a one-tap approve/decline, the kill switch, the autonomy level.
5. **Telegram adapter** at L0, then L1 after an evaluation set of real customer-style messages (Italian and English, with abuse and
   injection attempts), run like `ai-eval/` and measured.
6. WhatsApp adapter, same path. Phone much later.

## Efficiency (it must not slow the floor or cost money)
- The customer agent needs far less than the staff assistant: mostly a few intents (hours, menu, booking, cancel, human). Extract
  with a schema and run the rest as plain code; the model is for understanding the language, not for deciding.
- Try the smallest model that passes the evaluation (3B to 4B, even CPU) before the 9B; measure replies per minute on the PC.
- One GPU is shared with the staff assistant and voice: customer traffic gets a separate, lower concurrency limit so a flood of
  messages can never make the waiters' assistant slow (they already have their own gate).

## Decisions AJ owes (not for tonight)
Which channel first (Telegram is the easy one); whether OpenClaw is wanted as a transport or skipped; the opening hours and booking
rules the agent may quote; the tone and name of the persona; who in the team gets the inbox; what the agent says about allergies
(proposed: only declared data plus "the kitchen confirms").
