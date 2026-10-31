/**
 * Short-lived Deepgram credentials for client devices.
 *
 * The account key never leaves the Worker. A paired device asks for a
 * token, the Worker mints one against Deepgram's /v1/auth/grant, and the
 * device opens its own streaming socket with that. A leaked token expires
 * in minutes; a leaked account key would not.
 */
import { HttpError } from "./util";

const AUTH_GRANT = "https://api.deepgram.com/v1/auth/grant";

/**
 * Deepgram's default TTL is 30 seconds, which is too tight for a phone
 * that may sit on a lock screen between asking and connecting. Ten minutes
 * is long enough to be practical and short enough that a stolen token is
 * worth little. Deepgram's own maximum is one hour.
 */
export const TOKEN_TTL_SECONDS = 600;

export interface GrantedToken {
  accessToken: string;
  expiresIn: number;
}

/** Speech-to-text settings the device should use. */
export interface ListenProfile {
  model: string;
  language: string;
  sampleRate: number;
  encoding: string;
  channels: number;
  /** Milliseconds of silence that close an utterance, server-side. */
  endpointingMs: number;
  utteranceEndMs: number;
  interimResults: boolean;
  smartFormat: boolean;
}

/**
 * Arabic runs on Nova-3, which covers ar including the Maghreb locales.
 *
 * Deepgram has no Arabic text-to-speech voice at all — Aura and Flux cover
 * English, Spanish and five European languages — so speech output stays on
 * our own Arabic engines. See DECISIONS.md D8.
 */
export const ARABIC_LISTEN_PROFILE: ListenProfile = {
  model: "nova-3",
  language: "ar",
  sampleRate: 16000,
  encoding: "linear16",
  channels: 1,
  // Long enough to survive a mid-sentence pause, short enough to feel
  // immediate. Mirrors the desktop endpointer's 700 ms.
  endpointingMs: 700,
  utteranceEndMs: 1000,
  interimResults: true,
  smartFormat: true,
};

export const SUPPORTED_LANGUAGES = [
  { code: "ar", label: "العربية", note: "تشمل اللهجات المغاربية" },
  { code: "multi", label: "متعدّد اللغات", note: "عربي وإنجليزي وفرنسي في جملة واحدة" },
  { code: "en", label: "الإنجليزية", note: "" },
  { code: "fr", label: "الفرنسية", note: "" },
] as const;

/** Build the streaming URL the device connects to. */
export function listenUrl(profile: ListenProfile): string {
  const params = new URLSearchParams({
    model: profile.model,
    language: profile.language,
    encoding: profile.encoding,
    sample_rate: String(profile.sampleRate),
    channels: String(profile.channels),
    interim_results: String(profile.interimResults),
    smart_format: String(profile.smartFormat),
    endpointing: String(profile.endpointingMs),
    utterance_end_ms: String(profile.utteranceEndMs),
    // Lets the device know a turn ended even when the final transcript is
    // empty, which is how barge-in and silence are told apart.
    vad_events: "true",
  });
  return `wss://api.deepgram.com/v1/listen?${params.toString()}`;
}

/** Mint a temporary token. Throws an HttpError the device can act on. */
export async function grantToken(apiKey: string, ttlSeconds = TOKEN_TTL_SECONDS): Promise<GrantedToken> {
  let response: Response;
  try {
    response = await fetch(AUTH_GRANT, {
      method: "POST",
      headers: {
        authorization: `Token ${apiKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({ ttl_seconds: ttlSeconds }),
    });
  } catch {
    throw new HttpError(504, "stt_unreachable", "تعذّر الوصول إلى خدمة التعرّف على الكلام");
  }

  if (!response.ok) {
    throw mapGrantError(response.status);
  }

  const body = (await response.json().catch(() => ({}))) as {
    access_token?: string;
    expires_in?: number;
  };
  if (!body.access_token) {
    throw new HttpError(502, "stt_bad_response", "خدمة التعرّف لم تُرجع رمزًا");
  }

  return { accessToken: body.access_token, expiresIn: body.expires_in ?? ttlSeconds };
}

export function mapGrantError(status: number): HttpError {
  switch (status) {
    case 401:
      // The configured account key is wrong — an operator problem, not the
      // device's, so it must not read as "your device is unauthorised".
      return new HttpError(502, "stt_key_invalid", "مفتاح خدمة التعرّف غير صحيح على الخادم");
    case 403:
      return new HttpError(
        502,
        "stt_key_forbidden",
        "مفتاح خدمة التعرّف يحتاج صلاحية Member لإصدار الرموز المؤقّتة",
      );
    case 429:
      return new HttpError(429, "stt_rate_limited", "تجاوزت حصّة خدمة التعرّف، حاول بعد قليل");
    default:
      return new HttpError(502, "stt_upstream_error", `خطأ من خدمة التعرّف (HTTP ${status})`);
  }
}
