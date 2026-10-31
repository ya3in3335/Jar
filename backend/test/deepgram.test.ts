import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
  ARABIC_LISTEN_PROFILE,
  SUPPORTED_LANGUAGES,
  TOKEN_TTL_SECONDS,
  listenUrl,
  mapGrantError,
} from "../src/deepgram";

const JSON_HEADERS = { "content-type": "application/json" };

let ipCounter = 0;
function freshIp(): Record<string, string> {
  return { "cf-connecting-ip": `198.18.0.${(ipCounter++ % 250) + 1}` };
}

async function pairedToken(): Promise<string> {
  const res = await SELF.fetch("https://jarvis.test/v1/hubs", {
    method: "POST",
    headers: { ...JSON_HEADERS, ...freshIp() },
    body: JSON.stringify({ deviceName: "Phone", platform: "android" }),
  });
  expect(res.status).toBe(201);
  return ((await res.json()) as { token: string }).token;
}

describe("listen profile", () => {
  it("uses Nova-3 for Arabic, the model that actually covers it", () => {
    expect(ARABIC_LISTEN_PROFILE.model).toBe("nova-3");
    expect(ARABIC_LISTEN_PROFILE.language).toBe("ar");
  });

  it("matches the capture format the device records in", () => {
    expect(ARABIC_LISTEN_PROFILE.encoding).toBe("linear16");
    expect(ARABIC_LISTEN_PROFILE.sampleRate).toBe(16000);
    expect(ARABIC_LISTEN_PROFILE.channels).toBe(1);
  });

  it("asks for interim results so the user sees words as they speak", () => {
    expect(ARABIC_LISTEN_PROFILE.interimResults).toBe(true);
  });
});

describe("listen url", () => {
  const url = listenUrl(ARABIC_LISTEN_PROFILE);

  it("is a secure websocket on the listen endpoint", () => {
    expect(url.startsWith("wss://api.deepgram.com/v1/listen?")).toBe(true);
  });

  it("carries every capture parameter", () => {
    const params = new URL(url.replace("wss://", "https://")).searchParams;
    expect(params.get("model")).toBe("nova-3");
    expect(params.get("language")).toBe("ar");
    expect(params.get("encoding")).toBe("linear16");
    expect(params.get("sample_rate")).toBe("16000");
  });

  it("enables endpointing and utterance end, which drive turn taking", () => {
    const params = new URL(url.replace("wss://", "https://")).searchParams;
    expect(Number(params.get("endpointing"))).toBeGreaterThan(0);
    expect(Number(params.get("utterance_end_ms"))).toBeGreaterThan(0);
    expect(params.get("vad_events")).toBe("true");
  });

  it("carries no credential: the token travels in a header", () => {
    expect(url.toLowerCase()).not.toContain("token");
    expect(url.toLowerCase()).not.toContain("key");
  });

  it("reflects a different language when one is chosen", () => {
    const url = listenUrl({ ...ARABIC_LISTEN_PROFILE, language: "multi" });
    expect(url).toContain("language=multi");
  });
});

describe("grant error mapping", () => {
  it("reports a bad account key as a server problem, not a device one", () => {
    // A 401 here means the operator's key is wrong. Passing it through as
    // 401 would tell the user to re-pair their phone, which is useless.
    const err = mapGrantError(401);
    expect(err.status).toBe(502);
    expect(err.code).toBe("stt_key_invalid");
  });

  it("names the Member permission a grant needs", () => {
    expect(mapGrantError(403).message).toContain("Member");
  });

  it("passes quota exhaustion through as 429", () => {
    expect(mapGrantError(429).status).toBe(429);
  });

  it("falls back to 502 for anything else", () => {
    expect(mapGrantError(500).status).toBe(502);
  });
});

describe("token endpoint", () => {
  it("refuses an unpaired caller", async () => {
    const res = await SELF.fetch("https://jarvis.test/v1/stt/token", {
      method: "POST",
      headers: JSON_HEADERS,
    });
    expect(res.status).toBe(401);
  });

  it("refuses GET", async () => {
    const token = await pairedToken();
    const res = await SELF.fetch("https://jarvis.test/v1/stt/token", {
      headers: { authorization: `Bearer ${token}` },
    });
    expect(res.status).toBe(405);
  });

  it("reports that recognition is not configured rather than failing oddly", async () => {
    const token = await pairedToken();
    const res = await SELF.fetch("https://jarvis.test/v1/stt/token", {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, ...JSON_HEADERS },
    });
    // No DEEPGRAM_API_KEY in the test config.
    expect(res.status).toBe(503);
    const body = (await res.json()) as { error: { code: string } };
    expect(body.error.code).toBe("stt_not_configured");
  });
});

describe("languages endpoint", () => {
  it("lists Arabic first and says Deepgram has no Arabic voice", async () => {
    const token = await pairedToken();
    const res = await SELF.fetch("https://jarvis.test/v1/stt/languages", {
      headers: { authorization: `Bearer ${token}` },
    });
    expect(res.status).toBe(200);

    const body = (await res.json()) as {
      languages: { code: string }[];
      default: string;
      ttsSupported: boolean;
    };
    expect(body.languages[0].code).toBe("ar");
    expect(body.default).toBe("ar");
    // The whole architecture rests on this being advertised honestly.
    expect(body.ttsSupported).toBe(false);
  });

  it("refuses an unpaired caller", async () => {
    const res = await SELF.fetch("https://jarvis.test/v1/stt/languages");
    expect(res.status).toBe(401);
  });
});

describe("supported languages", () => {
  it("every entry has a code and an Arabic label", () => {
    for (const language of SUPPORTED_LANGUAGES) {
      expect(language.code).toMatch(/^[a-z]{2}$|^multi$/);
      expect(language.label.trim()).not.toBe("");
    }
  });

  it("the default TTL is practical for a phone but still short", () => {
    expect(TOKEN_TTL_SECONDS).toBeGreaterThanOrEqual(60);
    expect(TOKEN_TTL_SECONDS).toBeLessThanOrEqual(3600);
  });
});
