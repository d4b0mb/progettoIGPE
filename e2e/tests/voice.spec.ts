import { expect, test, type Page } from "@playwright/test";
import { DESKTOP, demo, expectNoHorizontalScroll, signedIn } from "./helpers";

/**
 * Talking to the staff assistant. No real engine is involved: Chromium is started with a fake microphone (see
 * playwright.config.ts) and the voice routes and the assistant's chat route are answered here, so every run is the
 * same. What these tests check is the screen's own behaviour, above all the safety rule: what is said and transcribed
 * is sent as an ordinary message and never confirms a proposal; only the Confirm button does.
 */

const PROPOSAL_ID = "22222222-2222-4222-8222-222222222222";
const REPLY = "Stasera hanno prenotato Rossi e Bianchi.";
const TRANSCRIPT = "Chi ha prenotato stasera?";
const FULL_VOICE = { enabled: true, speechToText: true, textToSpeech: true };
/** The browser must be allowed the microphone; the fake-ui flag answers the question, the permission makes it sure. */
const MICROPHONE = { permissions: ["microphone" as const] };

/** A date a number of days from today, as YYYY-MM-DD on this machine's clock. */
function inDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** A valid WAV file: 8 kHz, mono, 16-bit, 0.1 s of silence. */
function silentWav(): Buffer {
  const sampleRate = 8000;
  const dataBytes = (sampleRate / 10) * 2;
  const wav = Buffer.alloc(44 + dataBytes); // the sample bytes stay 0: silence
  wav.write("RIFF", 0, "ascii");
  wav.writeUInt32LE(36 + dataBytes, 4);
  wav.write("WAVE", 8, "ascii");
  wav.write("fmt ", 12, "ascii");
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20); // PCM
  wav.writeUInt16LE(1, 22); // mono
  wav.writeUInt32LE(sampleRate, 24);
  wav.writeUInt32LE(sampleRate * 2, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write("data", 36, "ascii");
  wav.writeUInt32LE(dataBytes, 40);
  return wav;
}

interface MockOptions {
  status?: { enabled: boolean; speechToText: boolean; textToSpeech: boolean };
  transcript?: string;
  /** Answer /transcribe with this error status instead of the transcript. */
  transcribeStatus?: number;
  /** Let the assistant's reply carry a booking proposal. */
  withProposal?: boolean;
}

interface Calls {
  status: number;
  transcribe: { contentType: string; body: string }[];
  speak: { text: string; language?: string }[];
  chat: { message: string }[];
  confirm: string[];
}

/** Answers the voice routes and the assistant's routes with fixed replies, and records every call that reaches them. */
async function mockVoice(page: Page, options: MockOptions = {}): Promise<Calls> {
  const calls: Calls = { status: 0, transcribe: [], speak: [], chat: [], confirm: [] };
  const date = inDays(2);

  await page.route("**/api/v1/voice/status", async (route) => {
    calls.status++;
    await route.fulfill({ status: 200, contentType: "application/json", json: options.status ?? FULL_VOICE });
  });

  await page.route("**/api/v1/voice/transcribe", async (route) => {
    const request = route.request();
    calls.transcribe.push({
      contentType: request.headers()["content-type"] ?? "",
      // As latin1 so the recording's bytes cannot upset the text; the headers of each part are plain ASCII.
      body: request.postDataBuffer()?.toString("latin1") ?? "",
    });
    if (options.transcribeStatus) {
      // The shape the API uses for every error (see errors.ts). Its words must never reach the screen.
      await route.fulfill({
        status: options.transcribeStatus,
        contentType: "application/json",
        json: { status: options.transcribeStatus, detail: "The speech engine exploded" },
      });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", json: { text: options.transcript ?? TRANSCRIPT } });
  });

  await page.route("**/api/v1/voice/speak", async (route) => {
    calls.speak.push(route.request().postDataJSON());
    await route.fulfill({ status: 200, contentType: "audio/wav", body: silentWav() });
  });

  await page.route("**/api/v1/assistant/chat", async (route) => {
    calls.chat.push(route.request().postDataJSON());
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      json: {
        reply: REPLY,
        proposals: options.withProposal
          ? [
              {
                id: PROPOSAL_ID,
                kind: "CREATE_RESERVATION",
                guestName: "Rossi",
                partySize: 4,
                date,
                time: "21:00",
                tableNumber: 3,
                notes: "",
                expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
              },
            ]
          : [],
        notices: [],
      },
    });
  });

  await page.route("**/api/v1/assistant/proposals/*/confirm", async (route) => {
    calls.confirm.push(route.request().url());
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      json: { kind: "CREATE_RESERVATION", guestName: "Rossi", partySize: 4, date, time: "21:00", tableNumber: 3 },
    });
  });

  return calls;
}

const openButton = (page: Page) => page.getByRole("button", { name: "Open assistant" });
const sheet = (page: Page) => page.getByRole("dialog", { name: "Assistant" });
const talkButton = (page: Page) => sheet(page).getByRole("button", { name: "Talk", exact: true });
const stopButton = (page: Page) => sheet(page).getByRole("button", { name: "Stop and send", exact: true });
const messageBox = (page: Page) => sheet(page).getByLabel("Message", { exact: true });

/**
 * Taps the microphone, lets the fake device record for a few seconds, and taps again to send. (The fake device
 * beeps now and then, so a few seconds are enough for the recording to have heard something. If the quiet after a
 * beep has already ended the recording by itself, there is nothing left to tap.)
 */
async function speakIntoMicrophone(page: Page) {
  await talkButton(page).click();
  await expect(stopButton(page)).toBeVisible();
  await page.waitForTimeout(3000);
  if (await stopButton(page).isVisible()) await stopButton(page).click();
}

test.describe("talking to the staff assistant", () => {
  test("tap the microphone, tap again: the recording is transcribed and sent as an ordinary message", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, DESKTOP, MICROPHONE);
    const calls = await mockVoice(page);
    await page.goto("/reservations");

    await openButton(page).click();
    await expect(talkButton(page)).toBeVisible();
    await speakIntoMicrophone(page);

    // The transcript is the person's message, and the reply follows it.
    await expect(sheet(page).getByText(TRANSCRIPT, { exact: true })).toBeVisible();
    await expect(sheet(page).getByText(REPLY)).toBeVisible();

    expect(calls.transcribe).toHaveLength(1);
    expect(calls.transcribe[0].contentType).toMatch(/^multipart\/form-data; boundary=/);
    expect(calls.transcribe[0].body).toContain('name="audio"');
    expect(calls.transcribe[0].body).toMatch(/filename="speech\.(webm|ogg|mp4)"/);
    // The screen is in English, so that is the language the recording is read in.
    expect(calls.transcribe[0].body).toMatch(/name="language"\r?\n\r?\nen/);
    expect(calls.chat).toHaveLength(1);
    expect(calls.chat[0].message).toBe(TRANSCRIPT);
    expect(calls.confirm).toHaveLength(0);

    // Nothing is left listening or spinning: the microphone is ready for the next question.
    await expect(talkButton(page)).toBeVisible();
    await expect(sheet(page).getByRole("alert")).toHaveCount(0);

    await context.close();
  });

  test("with Speak replies on, the reply is sent to /voice/speak and an audio element is played", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, DESKTOP, MICROPHONE);
    const calls = await mockVoice(page);
    // Records every call to play() on an audio element: the test looks at what was asked of the player, not at the sound.
    await page.addInitScript(() => {
      const played: string[] = [];
      (window as unknown as { __played: string[] }).__played = played;
      const play = HTMLMediaElement.prototype.play;
      HTMLMediaElement.prototype.play = function (this: HTMLMediaElement) {
        played.push(this.src);
        return play.call(this);
      };
    });
    await page.goto("/reservations");

    await openButton(page).click();
    const speakSwitch = sheet(page).getByRole("switch", { name: "Speak replies" });
    await expect(speakSwitch).toBeVisible();
    // Off until the person turns it on.
    await expect(speakSwitch).not.toBeChecked();
    await speakSwitch.click();
    await expect(speakSwitch).toBeChecked();

    await messageBox(page).fill(TRANSCRIPT);
    await messageBox(page).press("Enter");
    await expect(sheet(page).getByText(REPLY)).toBeVisible();

    await expect.poll(() => calls.speak.length).toBe(1);
    expect(calls.speak[0].text).toBe(REPLY);
    expect(calls.speak[0].language).toBe("en");

    // The silent clip that readied the player when the switch was tapped, and then the reply itself: both are blobs.
    await expect.poll(() => page.evaluate(() => (window as unknown as { __played: string[] }).__played.length)).toBeGreaterThanOrEqual(2);
    const played = await page.evaluate(() => (window as unknown as { __played: string[] }).__played);
    expect(played.every((src) => src.startsWith("blob:"))).toBe(true);

    await context.close();
  });

  test("with Speak replies off (the default), nothing is sent to /voice/speak", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, DESKTOP, MICROPHONE);
    const calls = await mockVoice(page);
    await page.goto("/reservations");

    await openButton(page).click();
    await expect(sheet(page).getByRole("switch", { name: "Speak replies" })).not.toBeChecked();
    await messageBox(page).fill(TRANSCRIPT);
    await messageBox(page).press("Enter");
    await expect(sheet(page).getByText(REPLY)).toBeVisible();
    // Give a stray request time to show up.
    await page.waitForTimeout(500);
    expect(calls.speak).toHaveLength(0);

    await context.close();
  });

  test("a transcript never confirms a proposal: it is only a message, and the card still waits for its button", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, DESKTOP, MICROPHONE);
    const calls = await mockVoice(page, { transcript: "sì conferma", withProposal: true });
    await page.goto("/reservations");

    await openButton(page).click();
    await speakIntoMicrophone(page);

    await expect(sheet(page).getByText("sì conferma", { exact: true })).toBeVisible();
    const card = sheet(page).getByRole("region", { name: "New booking" });
    await expect(card).toBeVisible();
    expect(calls.chat).toHaveLength(1);
    expect(calls.chat[0].message).toBe("sì conferma");

    // The confirm route was never touched, and the card is still pending with its Confirm button.
    expect(calls.confirm).toHaveLength(0);
    await expect(card.getByRole("button", { name: "Confirm booking" })).toBeEnabled();
    await expect(sheet(page).getByText(/^Done: booking for/)).toHaveCount(0);

    await context.close();
  });

  test("when the server says voice is off there is no microphone and no voice settings", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, DESKTOP, MICROPHONE);
    const calls = await mockVoice(page, { status: { enabled: false, speechToText: false, textToSpeech: false } });
    await page.goto("/reservations");

    const answered = page.waitForResponse("**/api/v1/voice/status");
    await openButton(page).click();
    await answered;
    await expect(messageBox(page)).toBeVisible();

    // Asked when the sheet opened (a development build, which runs effects twice, may ask once more).
    expect(calls.status).toBeGreaterThanOrEqual(1);
    await expect(talkButton(page)).toHaveCount(0);
    await expect(sheet(page).getByRole("switch")).toHaveCount(0);
    await expect(sheet(page).getByRole("group", { name: "Voice settings" })).toHaveCount(0);
    // The text chat is untouched.
    await messageBox(page).fill(TRANSCRIPT);
    await messageBox(page).press("Enter");
    await expect(sheet(page).getByText(REPLY)).toBeVisible();

    await context.close();
  });

  test("voice that is on without speech to text shows no microphone", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, DESKTOP, MICROPHONE);
    await mockVoice(page, { status: { enabled: true, speechToText: false, textToSpeech: true } });
    await page.goto("/reservations");

    const answered = page.waitForResponse("**/api/v1/voice/status");
    await openButton(page).click();
    await answered;
    await expect(messageBox(page)).toBeVisible();
    await expect(talkButton(page)).toHaveCount(0);
    await expect(sheet(page).getByRole("switch")).toHaveCount(0);

    await context.close();
  });

  test("the speaker switch is only there when the server can speak", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, DESKTOP, MICROPHONE);
    await mockVoice(page, { status: { enabled: true, speechToText: true, textToSpeech: false } });
    await page.goto("/reservations");

    await openButton(page).click();
    await expect(talkButton(page)).toBeVisible();
    await expect(sheet(page).getByRole("switch", { name: "Send automatically" })).toBeVisible();
    await expect(sheet(page).getByRole("switch", { name: "Speak replies" })).toHaveCount(0);

    await context.close();
  });

  test("a failed transcription shows a fixed notice, never the server's words, and the text box still works", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, DESKTOP, MICROPHONE);
    const calls = await mockVoice(page, { transcribeStatus: 503 });
    await page.goto("/reservations");

    await openButton(page).click();
    await speakIntoMicrophone(page);

    const alert = sheet(page).getByRole("alert");
    await expect(alert).toContainText("Voice is not available right now. You can still type your message.");
    await expect(sheet(page).getByText("The speech engine exploded")).toHaveCount(0);
    expect(calls.transcribe).toHaveLength(1);
    // Nothing was sent to the assistant, and nothing is left spinning.
    expect(calls.chat).toHaveLength(0);
    await expect(talkButton(page)).toBeVisible();

    // Typing works exactly as before.
    await expect(messageBox(page)).toBeEnabled();
    await messageBox(page).fill(TRANSCRIPT);
    await messageBox(page).press("Enter");
    await expect(sheet(page).getByText(REPLY)).toBeVisible();
    expect(calls.chat).toHaveLength(1);
    expect(calls.chat[0].message).toBe(TRANSCRIPT);

    await context.close();
  });

  // SKIPPED ON PURPOSE: localhost is always a secure context, so a page that is not secure (plain http on a LAN
  // address, where one explanatory line replaces the microphone) cannot be made here. Try it by hand on a real
  // http://<lan-address> page: the line "The microphone needs a secure connection (https)..." must show, with no
  // microphone button.
  test.skip("on a page that is not a secure context the microphone is replaced by one explanatory line", () => {});

  test("on a phone the microphone button is big enough, fits in the sheet and nothing scrolls sideways", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, { width: 375, height: 700 }, MICROPHONE);
    await mockVoice(page);
    await page.goto("/reservations");

    await openButton(page).click();
    await expect(talkButton(page)).toBeVisible();
    const box = (await talkButton(page).boundingBox())!;
    expect(box.width).toBeGreaterThanOrEqual(44);
    expect(box.height).toBeGreaterThanOrEqual(44);
    const sheetBox = (await sheet(page).boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(sheetBox.x);
    expect(box.x + box.width).toBeLessThanOrEqual(sheetBox.x + sheetBox.width);
    // The Send button still fits beside it.
    const sendBox = (await sheet(page).getByRole("button", { name: "Send", exact: true }).boundingBox())!;
    expect(sendBox.x + sendBox.width).toBeLessThanOrEqual(sheetBox.x + sheetBox.width);
    await expectNoHorizontalScroll(page);

    await context.close();
  });
});
