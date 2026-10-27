// Shared paths and loaders for the voice-lab scripts.
import { copyFileSync, existsSync, mkdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

export const LAB_DIR = fileURLToPath(new URL("../", import.meta.url));
export const OUT_DIR = join(LAB_DIR, "out");
export const PHRASES_PATH = join(LAB_DIR, "phrases.json");
export const CANDIDATES_PATH = join(LAB_DIR, "candidates.json");
export const CANDIDATES_EXAMPLE_PATH = join(LAB_DIR, "candidates.example.json");

export function ensureDir(dir) {
  mkdirSync(dir, { recursive: true });
  return dir;
}

/**
 * Command-line arguments: --key value, --key=value, --flag (listed in booleans) and positional words.
 * Returns { flags, positional }. Pure.
 */
export function parseArgs(argv, { booleans = [] } = {}) {
  const flags = {};
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) { positional.push(arg); continue; }
    const body = arg.slice(2);
    const eq = body.indexOf("=");
    if (eq >= 0) { flags[body.slice(0, eq)] = body.slice(eq + 1); continue; }
    if (booleans.includes(body)) { flags[body] = true; continue; }
    const next = argv[i + 1];
    if (next === undefined || next.startsWith("--")) flags[body] = true;
    else { flags[body] = next; i++; }
  }
  return { flags, positional };
}

/** base + path with exactly one slash between them. Pure. */
export function joinUrl(base, path) {
  return `${String(base).replace(/\/+$/, "")}/${String(path).replace(/^\/+/, "")}`;
}

/** A name that is safe as a file or folder name: anything outside [A-Za-z0-9._-] becomes "_". Pure. */
export function safeName(name) {
  return String(name).replace(/[^A-Za-z0-9._-]/g, "_");
}

/** File extension for an OpenAI "response_format" value. Pure. */
export function extensionForFormat(format) {
  const known = { mp3: "mp3", wav: "wav", opus: "opus", flac: "flac", aac: "aac", pcm: "pcm" };
  return known[String(format ?? "mp3").toLowerCase()] ?? "mp3";
}

/**
 * Problems found in a candidates config for one kind ("stt" or "tts"), as a list of readable strings.
 * An empty list means it looks usable. Pure.
 */
export function validateCandidates(config, kind) {
  const problems = [];
  const list = config?.[kind];
  if (!Array.isArray(list) || list.length === 0) return [`"${kind}" must be a list with at least one candidate`];
  const seen = new Set();
  list.forEach((c, i) => {
    const where = `${kind}[${i}]${c?.name ? ` (${c.name})` : ""}`;
    if (!c || typeof c.name !== "string" || !c.name) problems.push(`${where}: "name" is missing`);
    else if (seen.has(safeName(c.name))) problems.push(`${where}: duplicate name`);
    else seen.add(safeName(c.name));
    if (typeof c?.baseUrl !== "string" || !/^https?:\/\//.test(c.baseUrl)) problems.push(`${where}: "baseUrl" must start with http:// or https://`);
    if (typeof c?.model !== "string" || !c.model) problems.push(`${where}: "model" is missing`);
    if (kind === "tts" && (typeof c?.voices !== "object" || c.voices === null || Object.keys(c.voices).length === 0)) problems.push(`${where}: "voices" must be an object such as { "it": "if_sara", "en": "bm_george" }`);
    if (c?.headers !== undefined && (typeof c.headers !== "object" || c.headers === null || Array.isArray(c.headers))) problems.push(`${where}: "headers" must be an object`);
  });
  return problems;
}

/** Reads phrases.json ({ stt: [...], tts: [...] }). */
export function loadPhrases() {
  return JSON.parse(readFileSync(PHRASES_PATH, "utf8"));
}

/**
 * Reads candidates.json and returns the list for "stt" or "tts". When the file does not exist it copies
 * candidates.example.json to candidates.json, tells the user what to do and exits (nothing has been called yet).
 */
export function loadCandidates(kind) {
  if (!existsSync(CANDIDATES_PATH)) {
    copyFileSync(CANDIDATES_EXAMPLE_PATH, CANDIDATES_PATH);
    console.log(`voice-lab/candidates.json did not exist, so I created it from candidates.example.json.
Open it and check that each "baseUrl" points at a speech server that is running
(speech to text: usually http://localhost:8000, text to speech: usually http://localhost:8880), then run this command again.
The file is for your own settings and is not committed.`);
    process.exit(1);
  }
  let config;
  try {
    config = JSON.parse(readFileSync(CANDIDATES_PATH, "utf8"));
  } catch (error) {
    console.error(`voice-lab/candidates.json is not valid JSON: ${error.message}`);
    process.exit(1);
  }
  const problems = validateCandidates(config, kind);
  if (problems.length) {
    console.error(`voice-lab/candidates.json has problems:\n  - ${problems.join("\n  - ")}`);
    process.exit(1);
  }
  return config[kind];
}
