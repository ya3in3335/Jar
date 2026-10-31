import { describe, expect, it } from "vitest";
import {
  ARABIC_MALE_VOICES,
  VOICE_DEFAULTS,
  buildSsml,
  escapeXml,
  mapAzureError,
  parseVoiceRequest,
} from "../src/voice";

const base = { text: "مرحبا" };

describe("voice request parsing", () => {
  it("applies the Algerian male default voice and prosody", () => {
    const v = parseVoiceRequest(base);
    expect(v.voice).toBe("ar-DZ-IsmaelNeural");
    expect(v.pitch).toBe("-15Hz");
    expect(v.rate).toBe("-8%");
    expect(v.style).toBe("neutral");
  });

  it("normalises whitespace and strips control characters", () => {
    const v = parseVoiceRequest({ text: "  مرحبا\u0000   بك  " });
    expect(v.text).toBe("مرحبا بك");
  });

  it("accepts numeric prosody and signs it", () => {
    const v = parseVoiceRequest({ ...base, pitch: -20, rate: 10 });
    expect(v.pitch).toBe("-20Hz");
    expect(v.rate).toBe("+10%");
  });

  it("rejects an empty text", () => {
    expect(() => parseVoiceRequest({ text: "   " })).toThrow(/must not be empty/);
  });

  it("rejects text past the character limit", () => {
    expect(() => parseVoiceRequest({ text: "ا".repeat(3001) })).toThrow(/exceeds 3000/);
  });

  it("counts Arabic code points, not UTF-16 units", () => {
    // 3000 Arabic characters is exactly at the limit, not over it.
    expect(() => parseVoiceRequest({ text: "ا".repeat(3000) })).not.toThrow();
  });

  it("rejects a voice id that is not a neural voice", () => {
    expect(() => parseVoiceRequest({ ...base, voice: "robot9000" })).toThrow(/neural voice id/);
  });

  it("rejects out-of-range pitch", () => {
    expect(() => parseVoiceRequest({ ...base, pitch: "-80Hz" })).toThrow(/between -50Hz/);
  });

  it("rejects an unknown style", () => {
    expect(() => parseVoiceRequest({ ...base, style: "angry" })).toThrow(/must be one of/);
  });

  it("rejects a non-object body", () => {
    expect(() => parseVoiceRequest("مرحبا")).toThrow(/JSON object/);
  });
});

describe("SSML construction", () => {
  it("wraps the text with voice and prosody", () => {
    const ssml = buildSsml(parseVoiceRequest(base));
    expect(ssml).toContain('<voice name="ar-DZ-IsmaelNeural">');
    expect(ssml).toContain('<prosody pitch="-15Hz" rate="-8%">');
    expect(ssml).toContain("مرحبا");
  });

  it("derives xml:lang from the voice id", () => {
    expect(buildSsml(parseVoiceRequest({ ...base, voice: "ar-SA-HamedNeural" }))).toContain('xml:lang="ar-SA"');
  });

  it("escapes characters that would break the document", () => {
    const ssml = buildSsml(parseVoiceRequest({ text: 'قال <b>"مرحبا"</b> & غادر' }));
    expect(ssml).toContain("&lt;b&gt;");
    expect(ssml).toContain("&amp;");
    expect(ssml).toContain("&quot;");
    expect(ssml).not.toMatch(/<b>/);
  });

  it("cannot be escaped out of by injected SSML", () => {
    const ssml = buildSsml(parseVoiceRequest({ text: '</prosody></voice><voice name="x">hacked' }));
    // Exactly one real voice element survives.
    expect(ssml.match(/<voice name=/g)).toHaveLength(1);
    expect(ssml).toContain("&lt;/prosody&gt;");
  });

  it("shifts prosody per style without exceeding the limits", () => {
    const excited = buildSsml(parseVoiceRequest({ ...base, style: "excited" }));
    expect(excited).toContain('pitch="-9Hz"'); // -15 + 6
    expect(excited).toContain('rate="+4%"'); // -8 + 12

    const calm = buildSsml(parseVoiceRequest({ ...base, style: "calm" }));
    expect(calm).toContain('pitch="-17Hz"');
    expect(calm).toContain('rate="-14%"');
  });

  it("clamps a style shift at the boundary", () => {
    const ssml = buildSsml(parseVoiceRequest({ ...base, pitch: "-50Hz", style: "concerned" }));
    expect(ssml).toContain('pitch="-50Hz"'); // clamped, not -53
  });

  it("leaves prosody untouched for the neutral style", () => {
    const ssml = buildSsml(parseVoiceRequest({ ...base, pitch: "+5Hz", rate: "+0%", style: "neutral" }));
    expect(ssml).toContain('pitch="+5Hz"');
    expect(ssml).toContain('rate="+0%"');
  });
});

describe("escapeXml", () => {
  it("leaves Arabic text alone", () => {
    expect(escapeXml("مرحبا بك في جارفيس")).toBe("مرحبا بك في جارفيس");
  });
});

describe("voice catalogue", () => {
  it("lists the default voice first", () => {
    expect(ARABIC_MALE_VOICES[0].id).toBe(VOICE_DEFAULTS.voice);
  });

  it("holds only well-formed neural voice ids", () => {
    for (const v of ARABIC_MALE_VOICES) {
      expect(v.id).toMatch(/^ar-[A-Z]{2}-[A-Za-z]+Neural$/);
      expect(v.name.trim()).not.toBe("");
    }
  });
});

describe("upstream error mapping", () => {
  it("does not leak a credential problem as a client error", () => {
    const err = mapAzureError(401);
    expect(err.status).toBe(502);
    expect(err.code).toBe("voice_auth_failed");
  });

  it("passes a quota problem through as 429", () => {
    expect(mapAzureError(429).status).toBe(429);
  });

  it("reports a rejected request as 400", () => {
    expect(mapAzureError(400).code).toBe("voice_bad_request");
  });

  it("falls back to 502 for anything else", () => {
    expect(mapAzureError(500).status).toBe(502);
  });
});
