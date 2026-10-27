// Text normalisation and error rates for comparing what was said with what the engine heard.
// Pure functions, no dependencies.
//
// IMPORTANT: all of this is APPROXIMATE. Engines write numbers, times and names in different ways
// ("20:30", "8.30 pm", "otto e mezza", "O'Brien" or "OBrien"). The normaliser maps the common ones to the same
// words so that a harmless spelling difference does not count as an error, but it cannot catch every case:
//   - numbers 0-999 become words; longer digit strings (phone numbers) are read digit by digit, so a phone
//     number written "331 223 4455" by the engine will still not match "tre tre uno due due tre ...". Compare
//     engines with each other on those phrases rather than trusting the absolute score.
//   - times such as 20:30 become "venti e trenta" (it) or "twenty thirty" (en); "otto e mezza", "un quarto"
//     and "mezzogiorno" are mapped to the same form for Italian. "8 pm" is NOT turned into 20.
//   - English numbers have no "and" ("one hundred twenty"), Italian ones are written as one word ("centoventi").
// Accents are stripped ONLY for comparison; the original texts are never changed.

const IT_ONES = ["zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove"];
const IT_TENS = [null, null, "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta"];
const EN_ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"];
const EN_TENS = [null, null, "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];

function checkRange(n) {
  if (!Number.isInteger(n) || n < 0 || n > 999) throw new RangeError("Only whole numbers from 0 to 999 are supported");
}

/** 0-999 in Italian words: 21 -> "ventuno", 23 -> "ventitré", 108 -> "centotto", 180 -> "centottanta". */
export function numberToWordsIt(n) {
  checkRange(n);
  const below100 = (v) => {
    if (v < 20) return IT_ONES[v];
    const tens = Math.floor(v / 10);
    const units = v % 10;
    if (units === 0) return IT_TENS[tens];
    const stem = units === 1 || units === 8 ? IT_TENS[tens].slice(0, -1) : IT_TENS[tens]; // ventuno, ventotto
    return stem + (units === 3 ? "tré" : IT_ONES[units]);
  };
  if (n < 100) return below100(n);
  const hundreds = Math.floor(n / 100);
  const rest = n % 100;
  let base = hundreds === 1 ? "cento" : `${IT_ONES[hundreds]}cento`;
  if (rest === 0) return base;
  if (rest === 8 || (rest >= 80 && rest < 90)) base = base.slice(0, -1); // centotto, centottanta
  return base + below100(rest);
}

/** 0-999 in English words, without "and": 21 -> "twenty one", 120 -> "one hundred twenty". */
export function numberToWordsEn(n) {
  checkRange(n);
  const below100 = (v) => {
    if (v < 20) return EN_ONES[v];
    const units = v % 10;
    return units === 0 ? EN_TENS[Math.floor(v / 10)] : `${EN_TENS[Math.floor(v / 10)]} ${EN_ONES[units]}`;
  };
  if (n < 100) return below100(n);
  const rest = n % 100;
  const head = `${EN_ONES[Math.floor(n / 100)]} hundred`;
  return rest === 0 ? head : `${head} ${below100(rest)}`;
}

const isItalian = (lang) => String(lang ?? "").toLowerCase().startsWith("it");

/** A digit string as words: 0-999 as a number, anything longer or with a leading zero digit by digit. */
function digitsToWords(digits, lang) {
  const toWords = isItalian(lang) ? numberToWordsIt : numberToWordsEn;
  if (digits.length > 3 || (digits.length > 1 && digits.startsWith("0"))) {
    return [...digits].map((d) => toWords(Number(d))).join(" ");
  }
  return toWords(Number(digits));
}

/** "20:30" -> "venti e trenta" (it) / "twenty thirty" (en). "20:00" -> "venti" / "twenty". */
function timeToWords(hour, minute, lang) {
  const toWords = isItalian(lang) ? numberToWordsIt : numberToWordsEn;
  if (minute === 0) return toWords(hour);
  if (isItalian(lang)) return `${toWords(hour)} e ${toWords(minute)}`;
  return minute < 10 ? `${toWords(hour)} oh ${toWords(minute)}` : `${toWords(hour)} ${toWords(minute)}`;
}

/**
 * The text as it will be compared: lower case, times and numbers as words, no accents, no punctuation,
 * single spaces. lang is "it" or "en" (anything starting with "it" is Italian, everything else English).
 */
export function normalizeText(text, lang = "it") {
  const italian = isItalian(lang);
  let s = String(text ?? "").toLowerCase();
  s = s.replace(/[‘’ʼ`´]/g, "'");

  // Times first, before the plain numbers: 20:30 or 20.30 (hour 0-24, minute 00-59).
  s = s.replace(/(?<![\d:.])(\d{1,2})[:.](\d{2})(?!\d)/g, (whole, h, m) => {
    const hour = Number(h);
    const minute = Number(m);
    return hour <= 24 && minute <= 59 ? ` ${timeToWords(hour, minute, lang)} ` : whole;
  });

  if (italian) {
    // Spoken forms of the same times, so "otto e mezza" matches "8:30" (-> "otto e trenta").
    s = s.replace(/\bmezzogiorno\b/g, "dodici");
    s = s.replace(/\be\s+mezz[ao]\b/g, "e trenta");
    s = s.replace(/\be\s+un\s+quarto\b/g, "e quindici");
    s = s.replace(/\be\s+tre\s+quarti\b/g, "e quarantacinque");
  } else {
    s = s.replace(/\b([ap])\.\s?m\b\.?/g, "$1m"); // p.m. -> pm
  }

  s = s.replace(/\d+/g, (digits) => ` ${digitsToWords(digits, lang)} `);

  // Elision (Italian "un'allergia", "l'ordine") separates words; in English ("don't", "O'Brien") the apostrophe just disappears.
  s = s.replace(/'/g, italian ? " " : "");

  s = s.normalize("NFD").replace(/\p{M}/gu, ""); // strip accents, for comparison only
  s = s.replace(/[^\p{L}\p{N}\s]/gu, " "); // punctuation, symbols, hyphens
  return s.replace(/\s+/g, " ").trim();
}

/** Words of the normalised text ([] for an empty text). */
export function normalizedWords(text, lang = "it") {
  const normalised = normalizeText(text, lang);
  return normalised ? normalised.split(" ") : [];
}

/** Edit distance (insert, delete, substitute all cost 1) between two arrays or two strings. */
export function levenshtein(a, b) {
  const left = typeof a === "string" ? Array.from(a) : a;
  const right = typeof b === "string" ? Array.from(b) : b;
  if (left.length === 0) return right.length;
  if (right.length === 0) return left.length;
  let previous = Array.from({ length: right.length + 1 }, (_, j) => j);
  for (let i = 1; i <= left.length; i++) {
    const current = [i];
    for (let j = 1; j <= right.length; j++) {
      const cost = left[i - 1] === right[j - 1] ? 0 : 1;
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost);
    }
    previous = current;
  }
  return previous[right.length];
}

const rate = (distance, referenceLength, hypothesisLength) => (referenceLength === 0 ? (hypothesisLength === 0 ? 0 : 1) : distance / referenceLength);

/**
 * Word error rate: word edits needed to turn the hypothesis into the reference, divided by the number of
 * reference words. 0 is perfect; it can go above 1 when the engine adds many words. An empty reference gives
 * 0 for an empty hypothesis and 1 otherwise. Both texts are normalised first.
 */
export function wordErrorRate(reference, hypothesis, lang = "it") {
  const ref = normalizedWords(reference, lang);
  const hyp = normalizedWords(hypothesis, lang);
  return rate(levenshtein(ref, hyp), ref.length, hyp.length);
}

/** Character error rate, the same idea on characters of the normalised text (spaces count). */
export function charErrorRate(reference, hypothesis, lang = "it") {
  const ref = Array.from(normalizeText(reference, lang));
  const hyp = Array.from(normalizeText(hypothesis, lang));
  return rate(levenshtein(ref, hyp), ref.length, hyp.length);
}
