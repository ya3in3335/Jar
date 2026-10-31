import { HttpError } from "./util";

/**
 * Voice request validation.
 *
 * Defaults match DECISIONS.md D5: a deep, slightly slowed Algerian Arabic
 * male voice. `style` is accepted and reported back, but it is applied with
 * prosody only — the Edge backend does not do true expressive synthesis, and
 * the response header says so rather than implying otherwise.
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
    throw new HttpError(
      400,
      "invalid_voice",
      "'voice' must be an Edge neural voice id such as ar-DZ-IsmaelNeural",
    );
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

export function upstreamVoiceBody(v: VoiceRequest): Record<string, unknown> {
  return { text: v.text, voice: v.voice, pitch: v.pitch, rate: v.rate, provider: "edge" };
}
