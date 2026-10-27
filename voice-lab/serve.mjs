// A tiny local web server for the voice lab: recording page, listening page, and a safe upload endpoint.
//   node voice-lab/serve.mjs
// Then open http://127.0.0.1:8099/record.html (record the phrases) or http://127.0.0.1:8099/out/listen.html.
// It listens ONLY on 127.0.0.1 (this computer), which browsers also treat as a secure context, so the
// microphone works without HTTPS. No dependencies.
//
//   GET  /                      redirects to /record.html
//   GET  /<file>                voice-lab/web/<file>
//   GET  /out/<path>            voice-lab/out/<path>      (recordings, tts audio, listen.html ...)
//   GET  /lib/<file>            voice-lab/lib/<file>      (record.js shares wav.mjs with the tests)
//   GET  /phrases.json          voice-lab/phrases.json
//   GET  /list?dir=recordings   { dir, files: [{ name, size, mtimeMs }] }   (dir: recordings or ratings)
//   POST /save?name=recordings%2Fstt-it-01.wav   writes the request body to voice-lab/out/recordings/stt-it-01.wav
import http from "node:http";
import { createReadStream } from "node:fs";
import { mkdir, readdir, rename, stat, unlink, writeFile } from "node:fs/promises";
import { randomBytes } from "node:crypto";
import { extname, join, relative, resolve } from "node:path";
import { LAB_DIR, OUT_DIR, PHRASES_PATH } from "./lib/config.mjs";
import { isInsideRelative, isLocalRequest, MAX_UPLOAD_BYTES, parseListDir, parseSaveName } from "./lib/save-rules.mjs";
import { parseWav } from "./lib/wav.mjs";

const HOST = "127.0.0.1"; // never anything else
const PORT = Number(process.env.VOICE_LAB_PORT ?? 8099);
if (!Number.isInteger(PORT) || PORT < 1024 || PORT > 65535) {
  console.error("VOICE_LAB_PORT must be a whole number between 1024 and 65535");
  process.exit(1);
}

const WEB_DIR = join(LAB_DIR, "web");
const LIB_DIR = join(LAB_DIR, "lib");

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".csv": "text/csv; charset=utf-8",
  ".txt": "text/plain; charset=utf-8",
  ".wav": "audio/wav",
  ".mp3": "audio/mpeg",
  ".opus": "audio/ogg",
  ".ogg": "audio/ogg",
  ".flac": "audio/flac",
  ".aac": "audio/aac",
};

function send(res, status, body, headers = {}) {
  const isJson = typeof body === "object" && !Buffer.isBuffer(body);
  const payload = isJson ? JSON.stringify(body) : body;
  res.writeHead(status, { "Content-Type": isJson ? "application/json; charset=utf-8" : "text/plain; charset=utf-8", "Cache-Control": "no-store", ...headers });
  res.end(payload);
}

/** Maps a URL path to a file under one of the three served folders, or null. */
function resolveStatic(pathname) {
  let decoded;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    return null;
  }
  if (decoded.includes("\0") || decoded.includes("\\")) return null;
  if (decoded === "/phrases.json") return PHRASES_PATH;
  let root = WEB_DIR;
  let rest = decoded;
  if (decoded.startsWith("/out/")) { root = OUT_DIR; rest = decoded.slice(4); }
  else if (decoded.startsWith("/lib/")) { root = LIB_DIR; rest = decoded.slice(4); }
  const target = resolve(root, "." + rest);
  return isInsideRelative(relative(root, target)) ? target : null;
}

async function serveFile(req, res, file) {
  let info;
  try {
    info = await stat(file);
  } catch {
    return send(res, 404, "Not found");
  }
  if (!info.isFile()) return send(res, 404, "Not found");
  const type = TYPES[extname(file).toLowerCase()] ?? "application/octet-stream";
  const headers = { "Content-Type": type, "Cache-Control": "no-store", "Accept-Ranges": "bytes" };
  const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range ?? "");
  if (range && (range[1] !== "" || range[2] !== "")) {
    let start = range[1] === "" ? Math.max(0, info.size - Number(range[2])) : Number(range[1]);
    let end = range[1] === "" || range[2] === "" ? info.size - 1 : Math.min(Number(range[2]), info.size - 1);
    if (start > end || start >= info.size) {
      res.writeHead(416, { "Content-Range": `bytes */${info.size}` });
      return res.end();
    }
    res.writeHead(206, { ...headers, "Content-Range": `bytes ${start}-${end}/${info.size}`, "Content-Length": end - start + 1 });
    return req.method === "HEAD" ? res.end() : createReadStream(file, { start, end }).pipe(res);
  }
  res.writeHead(200, { ...headers, "Content-Length": info.size });
  if (req.method === "HEAD") return res.end();
  createReadStream(file).pipe(res);
}

/** Reads the whole body; once it passes the limit the rest is read and thrown away, and tooLarge is true. */
async function readBody(req) {
  const chunks = [];
  let size = 0;
  let tooLarge = false;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_UPLOAD_BYTES) { tooLarge = true; chunks.length = 0; continue; }
    if (!tooLarge) chunks.push(chunk);
  }
  return { body: Buffer.concat(chunks), tooLarge };
}

async function handleSave(req, res, url) {
  const parsed = parseSaveName(url.searchParams.get("name"));
  if (!parsed) return send(res, 400, { error: "name must look like recordings/stt-it-01.wav (folders: recordings, ratings; files: letters, digits . _ - and .wav or .json)" });
  const declared = Number(req.headers["content-length"]);
  if (Number.isFinite(declared) && declared > MAX_UPLOAD_BYTES) {
    req.resume();
    return send(res, 413, { error: "Upload is larger than 20 MB" }, { Connection: "close" });
  }
  const { body, tooLarge } = await readBody(req);
  if (tooLarge) return send(res, 413, { error: "Upload is larger than 20 MB" });
  if (body.length === 0) return send(res, 400, { error: "Empty body" });
  if (parsed.file.toLowerCase().endsWith(".json")) {
    try { JSON.parse(body.toString("utf8")); } catch { return send(res, 400, { error: "Body is not valid JSON" }); }
  } else {
    try { parseWav(new Uint8Array(body.buffer, body.byteOffset, body.byteLength)); } catch (error) { return send(res, 400, { error: `Body is not a WAV file: ${error.message}` }); }
  }
  const dir = join(OUT_DIR, parsed.dir);
  await mkdir(dir, { recursive: true });
  const target = join(dir, parsed.file);
  const temporary = `${target}.${randomBytes(4).toString("hex")}.tmp`;
  try {
    await writeFile(temporary, body);
    await rename(temporary, target);
  } catch (error) {
    await unlink(temporary).catch(() => {});
    throw error;
  }
  send(res, 200, { saved: `${parsed.dir}/${parsed.file}`, bytes: body.length });
}

async function handleList(res, url) {
  const dir = parseListDir(url.searchParams.get("dir"));
  if (!dir) return send(res, 400, { error: "dir must be recordings or ratings" });
  let entries = [];
  try {
    entries = await readdir(join(OUT_DIR, dir), { withFileTypes: true });
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const files = [];
  for (const entry of entries) {
    if (!entry.isFile() || entry.name.endsWith(".tmp")) continue;
    const info = await stat(join(OUT_DIR, dir, entry.name));
    files.push({ name: entry.name, size: info.size, mtimeMs: Math.round(info.mtimeMs) });
  }
  send(res, 200, { dir, files });
}

const server = http.createServer(async (req, res) => {
  try {
    if (!isLocalRequest({ host: req.headers.host, origin: req.headers.origin }, PORT)) return send(res, 403, "Forbidden");
    const url = new URL(req.url ?? "/", `http://${HOST}:${PORT}`);
    if (req.method === "POST" && url.pathname === "/save") return await handleSave(req, res, url);
    if (req.method === "GET" && url.pathname === "/list") return await handleList(res, url);
    if (req.method === "GET" || req.method === "HEAD") {
      if (url.pathname === "/") { res.writeHead(302, { Location: "/record.html" }); return res.end(); }
      const file = resolveStatic(url.pathname);
      if (!file) return send(res, 404, "Not found");
      return await serveFile(req, res, file);
    }
    send(res, 405, "Method not allowed", { Allow: "GET, HEAD, POST" });
  } catch (error) {
    console.error(error);
    if (!res.headersSent) send(res, 500, { error: "Server error" });
    else res.end();
  }
});

server.on("error", (error) => {
  console.error(error.code === "EADDRINUSE" ? `Port ${PORT} is already in use. Is serve.mjs already running? (or set VOICE_LAB_PORT)` : error);
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  console.log(`Voice lab server on http://${HOST}:${PORT}  (this computer only; press Ctrl+C to stop)`);
  console.log(`  Record the phrases:   http://${HOST}:${PORT}/record.html`);
  console.log(`  Listen and rate TTS:  http://${HOST}:${PORT}/out/listen.html   (after node voice-lab/tts-compare.mjs)`);
  console.log(`  Recordings are saved in ${join(OUT_DIR, "recordings")}`);
});
