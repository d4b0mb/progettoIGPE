// WAV helpers. Pure functions that work in Node AND in the browser (no node: imports, no Buffer),
// because web/record.js imports this same file (serve.mjs serves voice-lab/lib/ as /lib/).

const ascii = (view, offset, text) => {
  for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.charCodeAt(i));
};
const readAscii = (view, offset, length) => {
  let s = "";
  for (let i = 0; i < length; i++) s += String.fromCharCode(view.getUint8(offset + i));
  return s;
};

/**
 * The 44-byte header of a canonical PCM WAV file (RIFF, fmt chunk of 16 bytes, data chunk).
 * dataBytes is the size of the sample data that will follow.
 */
export function buildWavHeader({ dataBytes, sampleRate = 16000, channels = 1, bitsPerSample = 16 }) {
  const header = new Uint8Array(44);
  const view = new DataView(header.buffer);
  const blockAlign = (channels * bitsPerSample) / 8;
  ascii(view, 0, "RIFF");
  view.setUint32(4, 36 + dataBytes, true);
  ascii(view, 8, "WAVE");
  ascii(view, 12, "fmt ");
  view.setUint32(16, 16, true); // size of the fmt chunk
  view.setUint16(20, 1, true); // 1 = plain PCM
  view.setUint16(22, channels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * blockAlign, true); // bytes per second
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, bitsPerSample, true);
  ascii(view, 36, "data");
  view.setUint32(40, dataBytes, true);
  return header;
}

/** Float samples in -1..1 to 16-bit signed integers (clamped, asymmetric scaling as in the usual encoders). */
export function floatTo16BitPCM(samples) {
  const out = new Int16Array(samples.length);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    out[i] = Math.round(s < 0 ? s * 0x8000 : s * 0x7fff);
  }
  return out;
}

/** 16-bit PCM bytes (little endian) back to floats in -1..1. */
export function pcm16BytesToFloat(bytes, offset = 0, length = bytes.byteLength - offset) {
  const view = new DataView(bytes.buffer, bytes.byteOffset + offset, length);
  const out = new Float32Array(Math.floor(length / 2));
  for (let i = 0; i < out.length; i++) {
    const v = view.getInt16(i * 2, true);
    out[i] = v < 0 ? v / 0x8000 : v / 0x7fff;
  }
  return out;
}

/** A complete mono 16-bit WAV file (Uint8Array) from float samples. */
export function encodeWav(samples, sampleRate = 16000) {
  const pcm = floatTo16BitPCM(samples);
  const dataBytes = pcm.length * 2;
  const file = new Uint8Array(44 + dataBytes);
  file.set(buildWavHeader({ dataBytes, sampleRate, channels: 1, bitsPerSample: 16 }), 0);
  const view = new DataView(file.buffer);
  for (let i = 0; i < pcm.length; i++) view.setInt16(44 + i * 2, pcm[i], true);
  return file;
}

/**
 * Reads the format of a WAV file. Walks the chunks, so extra chunks such as LIST before "data" are fine.
 * Returns { audioFormat, channels, sampleRate, bitsPerSample, dataOffset, dataBytes, durationMs }.
 * Throws an Error when the bytes are not a WAV file.
 */
export function parseWav(bytes) {
  if (bytes.byteLength < 12) throw new Error("Too short to be a WAV file");
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (readAscii(view, 0, 4) !== "RIFF" || readAscii(view, 8, 4) !== "WAVE") throw new Error("Not a RIFF/WAVE file");
  let fmt = null;
  let offset = 12;
  while (offset + 8 <= bytes.byteLength) {
    const id = readAscii(view, offset, 4);
    const size = view.getUint32(offset + 4, true);
    const body = offset + 8;
    if (id === "fmt ") {
      fmt = {
        audioFormat: view.getUint16(body, true),
        channels: view.getUint16(body + 2, true),
        sampleRate: view.getUint32(body + 4, true),
        bitsPerSample: view.getUint16(body + 14, true),
      };
    } else if (id === "data") {
      if (!fmt) throw new Error("data chunk before fmt chunk");
      const dataBytes = Math.min(size, bytes.byteLength - body); // a streamed file may claim a huge size
      const bytesPerFrame = (fmt.channels * fmt.bitsPerSample) / 8;
      const durationMs = bytesPerFrame && fmt.sampleRate ? Math.round((dataBytes / bytesPerFrame / fmt.sampleRate) * 1000) : 0;
      return { ...fmt, dataOffset: body, dataBytes, durationMs };
    }
    offset = body + size + (size % 2); // chunks are padded to an even length
  }
  throw new Error("No data chunk found");
}

/** Averages several channels (an array of Float32Array of equal length) into one. */
export function downmixToMono(channels) {
  if (channels.length === 0) return new Float32Array(0);
  if (channels.length === 1) return Float32Array.from(channels[0]);
  const length = channels[0].length;
  const out = new Float32Array(length);
  for (const channel of channels) for (let i = 0; i < length; i++) out[i] += channel[i];
  for (let i = 0; i < length; i++) out[i] /= channels.length;
  return out;
}

/**
 * Linear-interpolation resampler. It has no low-pass filter, so strong downsampling can alias a little.
 * The recorder page uses the browser's OfflineAudioContext (properly filtered) and this function is the
 * plain-JS fallback and the thing the tests can exercise in Node.
 */
export function resampleLinear(input, fromRate, toRate) {
  if (fromRate <= 0 || toRate <= 0) throw new RangeError("Sample rates must be positive");
  if (fromRate === toRate) return Float32Array.from(input);
  const outLength = Math.round((input.length * toRate) / fromRate);
  const out = new Float32Array(outLength);
  const step = fromRate / toRate;
  for (let i = 0; i < outLength; i++) {
    const position = i * step;
    const low = Math.floor(position);
    const high = Math.min(low + 1, input.length - 1);
    const fraction = position - low;
    out[i] = input[Math.min(low, input.length - 1)] * (1 - fraction) + input[high] * fraction;
  }
  return out;
}
