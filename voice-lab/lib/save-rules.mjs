// Rules for what serve.mjs is allowed to write and list. Pure functions so they can be tested.

/** The only folders under voice-lab/out/ that the upload endpoint may write to or list. */
export const ALLOWED_DIRS = ["recordings", "ratings"];
/** The only file types that may be uploaded. */
export const ALLOWED_EXTENSIONS = ["wav", "json"];
/** Upload size limit in bytes (20 MB). */
export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

/**
 * Checks the ?name= of POST /save. It must be "<folder>/<file>" where folder is in ALLOWED_DIRS and file uses
 * only letters, digits, dot, underscore and dash, does not start with a dot and ends in .wav or .json.
 * Returns { dir, file } or null when the name is refused.
 */
export function parseSaveName(name) {
  if (typeof name !== "string" || name.length === 0 || name.length > 120) return null;
  const parts = name.split("/");
  if (parts.length !== 2) return null;
  const [dir, file] = parts;
  if (!ALLOWED_DIRS.includes(dir)) return null;
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(file)) return null;
  if (file.includes("..")) return null;
  const extension = file.slice(file.lastIndexOf(".") + 1).toLowerCase();
  if (!ALLOWED_EXTENSIONS.includes(extension) || file.lastIndexOf(".") < 1) return null;
  return { dir, file };
}

/** Checks the ?dir= of GET /list. Returns the folder name or null. */
export function parseListDir(dir) {
  return ALLOWED_DIRS.includes(dir) ? dir : null;
}

/**
 * True when the Host header (and the Origin header, if the browser sent one) belong to this local server.
 * Blocks other web pages from posting files here and DNS-rebinding tricks.
 */
export function isLocalRequest({ host, origin }, port) {
  const hosts = [`127.0.0.1:${port}`, `localhost:${port}`];
  if (!hosts.includes(String(host ?? "").toLowerCase())) return false;
  if (origin === undefined || origin === "") return true;
  return hosts.some((h) => origin.toLowerCase() === `http://${h}`);
}

/**
 * Takes the result of path.relative(root, target) and says whether target is strictly inside root:
 * not the root itself, not climbing out with "..", not on another drive (an absolute result).
 */
export function isInsideRelative(relativePath) {
  return relativePath !== "" && !relativePath.startsWith("..") && !/^([a-zA-Z]:)?[\\/]/.test(relativePath);
}
