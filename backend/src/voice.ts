import { HttpError } from "./util";

/**
 * Voice synthesis via Azure Speech.
 *
 * The default voice is ar-DZ-IsmaelNeural: Algerian Arabic, male, deep and
 * calm once pitch and rate are nudged down. See DECISIONS.md D5 for why the
 * licensed Azure endpoint is used rather than the Edge read-aloud one.
 */
export const VOICE_DEFAULTS = {
  voice: "ar-DZ-IsmaelNeural",
  pitch: "-15Hz",
  rate: "-8%",
} as const;

export const VOICE_LIMITS = {
  maxChars: 3000,
  maxBodyBytes: 16 * 1024,
  upstreamTimeoutMs: 25_000,
} as const;

export const VOICE_STYLES = ["neutral", "calm", "serious", "friendly", "excited", "concerned"] as const;
export type VoiceStyle = (typeof VOICE_STYLES)[number];

/** e.g. ar-DZ-IsmaelNeural, ar-SA-HamedNeural, ar-AE-HamdanNeural */
const VOICE_RE = /^[a-z]{2,3}-[A-Za-z]{2,4}(-[A-Za-z]+)?-[A-Za-z]+Neural$/;

/**
 * Arabic male neural voices, so a client can offer a picker without a
 * round trip. Not exhaustive — any valid Azure voice id is accepted.
 */
export const ARABIC_MALE_VOICES = [
  { id: "ar-DZ-IsmaelNeural", name: "إسماعيل", locale: "ar-DZ", localeName: "الجزائر" },
  { id: "ar-SA-HamedNeural", name: "حامد", locale: "ar-SA", localeName: "السعودية" },
  { id: "ar-AE-HamdanNeural", name: "حمدان", locale: "ar-AE", localeName: "الإمارات" },
  { id: "ar-EG-ShakirNeural", name: "شاكر", locale: "ar-EG", localeName: "مصر" },
  { id: "ar-MA-JamalNeural", name: "جمال", locale: "ar-MA", localeName: "المغرب" },
  { id: "ar-TN-HediNeural", name: "هادي", locale: "ar-TN", localeName: "تونس" },
  { id: "ar-LY-OmarNeural", name: "عمر", locale: "ar-LY", localeName: "ليبيا" },
  { id: "ar-JO-TaimNeural", name: "تيم", locale: "ar-JO", localeName: "الأردن" },
  { id: "ar-IQ-BasselNeural", name: "باسل", locale: "ar-IQ", localeName: "العراق" },
  { id: "ar-SY-LaithNeural", name: "ليث", locale: "ar-SY", localeName: "سوريا" },
] as const;

export interface VoiceRequest {
  text: string;
  voice: string;
  pitch: string;
  rate: string;
  style: VoiceStyle;
}

/** Normalise a signed prosody value to the "+NUnit" / "-NUnit" SSML form. */
function signedValue(
  value: unknown,
  unit: "Hz" | "%",
  min: number,
  max: number,
  fallback: string,
  name: string,
): string {
  if (value === undefined || value === null || value === "") return fallback;

  let text: string;
  if (typeof value === "number" && Number.isFinite(value)) {
    text = `${value >= 0 ? "+" : ""}${Math.round(value)}${unit}`;
  } else if (typeof value === "string") {
    text = value.trim();
  } else {
    throw new HttpError(400, "invalid_parameter", `'${name}' must be a string like "${fallback}"`);
  }

  const m = new RegExp(`^([+-]?)(\\d{1,3})${unit === "%" ? "%" : "Hz"}$`).exec(text);
  if (!m) throw new HttpError(400, "invalid_parameter", `'${name}' must look like "${fallback}"`);

  const n = (m[1] === "-" ? -1 : 1) * Number(m[2]);
  if (n < min || n > max) {
    throw new HttpError(400, "invalid_parameter", `'${name}' must be between ${min}${unit} and +${max}${unit}`);
  }
  return `${n >= 0 ? "+" : ""}${n}${unit}`;
}

export function parseVoiceRequest(body: unknown): VoiceRequest {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new HttpError(400, "invalid_json", "Body must be a JSON object");
  }
  const b = body as Record<string, unknown>;

  if (typeof b.text !== "string") {
    throw new HttpError(400, "invalid_parameter", "'text' is required");
  }
  // NFC so Arabic diacritics compare predictably; strip control chars.
  const text = b.text
    .normalize("NFC")
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "")
    .replace(/\s+/g, " ")
    .trim();

  if (!text) throw new HttpError(400, "invalid_parameter", "'text' must not be empty");
  if ([...text].length > VOICE_LIMITS.maxChars) {
    throw new HttpError(413, "text_too_long", `'text' exceeds ${VOICE_LIMITS.maxChars} characters`, {
      maxChars: VOICE_LIMITS.maxChars,
    });
  }

  const voice = b.voice === undefined || b.voice === "" ? VOICE_DEFAULTS.voice : String(b.voice).trim();
  if (!VOICE_RE.test(voice)) {
    throw new HttpError(400, "invalid_voice", "'voice' must be a neural voice id such as ar-DZ-IsmaelNeural");
  }

  const style = (b.style === undefined || b.style === "" ? "neutral" : String(b.style).toLowerCase()) as VoiceStyle;
  if (!(VOICE_STYLES as readonly string[]).includes(style)) {
    throw new HttpError(400, "invalid_parameter", `'style' must be one of: ${VOICE_STYLES.join(", ")}`);
  }

  return {
    text,
    voice,
    pitch: signedValue(b.pitch, "Hz", -50, 50, VOICE_DEFAULTS.pitch, "pitch"),
    rate: signedValue(b.rate, "%", -50, 100, VOICE_DEFAULTS.rate, "rate"),
    style,
  };
}

/** XML-escape text destined for an SSML document. */
export function escapeXml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

/**
 * Styles are expressed as prosody nudges on top of the caller's own pitch
 * and rate. Azure's `mstts:express-as` exists but is not available on the
 * Arabic voices, so pretending otherwise would be a lie in the output.
 */
const STYLE_PROSODY: Record<VoiceStyle, { pitchHz: number; ratePct: number }> = {
  neutral: { pitchHz: 0, ratePct: 0 },
  calm: { pitchHz: -2, ratePct: -6 },
  serious: { pitchHz: -4, ratePct: -3 },
  friendly: { pitchHz: +3, ratePct: +3 },
  excited: { pitchHz: +6, ratePct: +12 },
  concerned: { pitchHz: -3, ratePct: -8 },
};

function addSigned(base: string, delta: number, unit: "Hz" | "%", min: number, max: number): string {
  const n = Number(base.replace(unit, ""));
  const total = Math.max(min, Math.min(max, n + delta));
  return `${total >= 0 ? "+" : ""}${total}${unit}`;
}

/** Build the SSML document Azure Speech expects. */
export function buildSsml(v: VoiceRequest): string {
  const adjust = STYLE_PROSODY[v.style];
  const pitch = addSigned(v.pitch, adjust.pitchHz, "Hz", -50, 50);
  const rate = addSigned(v.rate, adjust.ratePct, "%", -50, 100);
  const locale = v.voice.split("-").slice(0, 2).join("-");

  return (
    `<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="${locale}">` +
    `<voice name="${v.voice}">` +
    `<prosody pitch="${pitch}" rate="${rate}">${escapeXml(v.text)}</prosody>` +
    `</voice></speak>`
  );
}

/** Azure's REST endpoint for a region, e.g. "francecentral". */
export function azureEndpoint(region: string): string {
  return `https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`;
}

/** Map an upstream Azure failure to an error the client can act on. */
export function mapAzureError(status: number): HttpError {
  switch (status) {
    case 401:
    case 403:
      return new HttpError(502, "voice_auth_failed", "The voice service rejected the configured key or region");
    case 429:
      return new HttpError(429, "voice_rate_limited", "Voice quota exceeded; try again shortly");
    case 400:
      return new HttpError(400, "voice_bad_request", "The voice service rejected the request (voice id or SSML)");
    default:
      return new HttpError(502, "voice_upstream_error", `Voice service error (HTTP ${status})`);
  }
}
