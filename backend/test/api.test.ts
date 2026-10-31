import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

const JSON_HEADERS = { "content-type": "application/json" };

interface Issued {
  hubId: string;
  deviceId: string;
  token: string;
  tokenExpires: number;
}

/**
 * Hub creation is limited to 5 per hour per source IP, so each test speaks
 * from its own address. The limiter itself is covered separately below.
 */
let ipCounter = 0;
function freshIp(): Record<string, string> {
  return { "cf-connecting-ip": `203.0.113.${(ipCounter++ % 250) + 1}` };
}

async function createHub(deviceName = "Phone", plat = "android"): Promise<Issued> {
  const res = await SELF.fetch("https://jarvis.test/v1/hubs", {
    method: "POST",
    headers: { ...JSON_HEADERS, ...freshIp() },
    body: JSON.stringify({ deviceName, platform: plat, capabilities: ["open_application"] }),
  });
  expect(res.status).toBe(201);
  return (await res.json()) as Issued;
}

function auth(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}`, ...JSON_HEADERS };
}

describe("health and docs", () => {
  it("reports service health", async () => {
    const res = await SELF.fetch("https://jarvis.test/health");
    expect(res.status).toBe(200);
    const body = (await res.json()) as { ok: boolean; service: string };
    expect(body.ok).toBe(true);
    expect(body.service).toBe("jarvis-backend");
  });

  it("serves a route listing", async () => {
    const res = await SELF.fetch("https://jarvis.test/");
    expect(res.status).toBe(200);
    expect(await res.text()).toContain("/v1/pair/start");
  });
});

describe("hub creation", () => {
  it("issues a well-formed device token", async () => {
    const hub = await createHub();
    expect(hub.token).toMatch(/^jv1\.[a-z2-9]{12}\.[a-z2-9]{10}\.[A-Za-z0-9_-]{40,60}$/);
    expect(hub.tokenExpires).toBeGreaterThan(Date.now());
  });

  it("rejects an unknown platform", async () => {
    const res = await SELF.fetch("https://jarvis.test/v1/hubs", {
      method: "POST",
      headers: { ...JSON_HEADERS, ...freshIp() },
      body: JSON.stringify({ deviceName: "X", platform: "toaster" }),
    });
    expect(res.status).toBe(400);
    const body = (await res.json()) as { error: { code: string } };
    expect(body.error.code).toBe("validation_error");
  });

  it("requires a JSON content type", async () => {
    const res = await SELF.fetch("https://jarvis.test/v1/hubs", {
      method: "POST",
      headers: freshIp(),
      body: "deviceName=X",
    });
    expect(res.status).toBe(415);
  });
});

describe("authentication", () => {
  it("refuses a missing token", async () => {
    const res = await SELF.fetch("https://jarvis.test/v1/me");
    expect(res.status).toBe(401);
  });

  it("refuses a tampered secret", async () => {
    const hub = await createHub();
    const parts = hub.token.split(".");
    parts[3] = "x".repeat(parts[3].length);
    const res = await SELF.fetch("https://jarvis.test/v1/me", {
      headers: auth(parts.join(".")),
    });
    expect(res.status).toBe(401);
  });

  it("returns the caller's own device", async () => {
    const hub = await createHub("Galaxy", "android");
    const res = await SELF.fetch("https://jarvis.test/v1/me", { headers: auth(hub.token) });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { device: { name: string; platform: string } };
    expect(body.device.name).toBe("Galaxy");
    expect(body.device.platform).toBe("android");
  });

  it("rotates a token and invalidates the old one", async () => {
    const hub = await createHub();
    const res = await SELF.fetch("https://jarvis.test/v1/auth/refresh", {
      method: "POST",
      headers: auth(hub.token),
    });
    expect(res.status).toBe(200);
    const { token: rotated } = (await res.json()) as { token: string };
    expect(rotated).not.toBe(hub.token);

    const oldTokenRes = await SELF.fetch("https://jarvis.test/v1/me", { headers: auth(hub.token) });
    expect(oldTokenRes.status).toBe(401);

    const newTokenRes = await SELF.fetch("https://jarvis.test/v1/me", { headers: auth(rotated) });
    expect(newTokenRes.status).toBe(200);
  });
});

describe("pairing", () => {
  it("pairs a second device with a one-time code", async () => {
    const phone = await createHub("Phone", "android");

    const startRes = await SELF.fetch("https://jarvis.test/v1/pair/start", {
      method: "POST",
      headers: auth(phone.token),
    });
    expect(startRes.status).toBe(200);
    const { code } = (await startRes.json()) as { code: string };
    expect(code).toMatch(/^[a-z2-9]{12}-\d{6}$/);

    const joinRes = await SELF.fetch("https://jarvis.test/v1/pair/complete", {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ code, deviceName: "Desktop", platform: "windows" }),
    });
    expect(joinRes.status).toBe(201);
    const pc = (await joinRes.json()) as Issued;
    expect(pc.hubId).toBe(phone.hubId);
    expect(pc.deviceId).not.toBe(phone.deviceId);

    // The code must not work twice.
    const replayRes = await SELF.fetch("https://jarvis.test/v1/pair/complete", {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ code, deviceName: "Intruder", platform: "other" }),
    });
    expect(replayRes.status).toBe(401);

    const listRes = await SELF.fetch("https://jarvis.test/v1/devices", { headers: auth(phone.token) });
    const { devices } = (await listRes.json()) as { devices: { name: string }[] };
    expect(devices.map((d) => d.name).sort()).toEqual(["Desktop", "Phone"]);
  });

  it("rejects a malformed code shape", async () => {
    const res = await SELF.fetch("https://jarvis.test/v1/pair/complete", {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ code: "nonsense", deviceName: "X", platform: "other" }),
    });
    expect(res.status).toBe(400);
  });
});

describe("devices", () => {
  it("revokes a device and kills its token", async () => {
    const phone = await createHub("Phone", "android");
    const startRes = await SELF.fetch("https://jarvis.test/v1/pair/start", {
      method: "POST",
      headers: auth(phone.token),
    });
    const { code } = (await startRes.json()) as { code: string };
    const joinRes = await SELF.fetch("https://jarvis.test/v1/pair/complete", {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ code, deviceName: "Desktop", platform: "windows" }),
    });
    const desktop = (await joinRes.json()) as Issued;

    const revokeRes = await SELF.fetch(`https://jarvis.test/v1/devices/${desktop.deviceId}`, {
      method: "DELETE",
      headers: auth(phone.token),
    });
    expect(revokeRes.status).toBe(200);

    const afterRes = await SELF.fetch("https://jarvis.test/v1/me", { headers: auth(desktop.token) });
    expect(afterRes.status).toBe(401);
  });

  it("will not let a device rename another device", async () => {
    const phone = await createHub();
    const res = await SELF.fetch("https://jarvis.test/v1/devices/aaaaaaaaaa", {
      method: "PATCH",
      headers: auth(phone.token),
      body: JSON.stringify({ name: "hijacked" }),
    });
    expect(res.status).toBe(403);
  });
});

describe("tasks", () => {
  it("refuses a task aimed at an unknown device", async () => {
    const phone = await createHub();
    const res = await SELF.fetch("https://jarvis.test/v1/tasks", {
      method: "POST",
      headers: auth(phone.token),
      body: JSON.stringify({ target: "laptop", kind: "instruction", instruction: "open chrome" }),
    });
    expect(res.status).toBe(404);
    const body = (await res.json()) as { error: { code: string } };
    expect(body.error.code).toBe("device_not_found");
  });

  it("refuses a task aimed at the sender", async () => {
    const phone = await createHub("Phone", "android");
    const res = await SELF.fetch("https://jarvis.test/v1/tasks", {
      method: "POST",
      headers: auth(phone.token),
      body: JSON.stringify({ target: "Phone", kind: "instruction", instruction: "x" }),
    });
    expect(res.status).toBe(400);
  });

  it("reports an offline target rather than queueing silently", async () => {
    const phone = await createHub("Phone", "android");
    const startRes = await SELF.fetch("https://jarvis.test/v1/pair/start", {
      method: "POST",
      headers: auth(phone.token),
    });
    const { code } = (await startRes.json()) as { code: string };
    await SELF.fetch("https://jarvis.test/v1/pair/complete", {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ code, deviceName: "Desktop", platform: "windows" }),
    });

    const res = await SELF.fetch("https://jarvis.test/v1/tasks", {
      method: "POST",
      headers: auth(phone.token),
      body: JSON.stringify({ target: "Desktop", kind: "instruction", instruction: "open chrome" }),
    });
    expect(res.status).toBe(409);
    const body = (await res.json()) as { error: { code: string } };
    expect(body.error.code).toBe("device_offline");
  });
});

describe("conversations and memory", () => {
  it("stores and reads back a conversation", async () => {
    const hub = await createHub();
    const putRes = await SELF.fetch("https://jarvis.test/v1/conversations/conv-001", {
      method: "PUT",
      headers: auth(hub.token),
      body: JSON.stringify({
        title: "اختبار",
        messages: [
          { role: "user", content: "افتح يوتيوب" },
          { role: "assistant", content: "تم" },
        ],
      }),
    });
    expect(putRes.status).toBe(200);

    const getRes = await SELF.fetch("https://jarvis.test/v1/conversations/conv-001", {
      headers: auth(hub.token),
    });
    const body = (await getRes.json()) as { title: string; messages: { content: string }[] };
    expect(body.title).toBe("اختبار");
    expect(body.messages).toHaveLength(2);
    expect(body.messages[0].content).toBe("افتح يوتيوب");
  });

  it("rejects a bad message role", async () => {
    const hub = await createHub();
    const res = await SELF.fetch("https://jarvis.test/v1/conversations/conv-002", {
      method: "PUT",
      headers: auth(hub.token),
      body: JSON.stringify({ messages: [{ role: "root", content: "x" }] }),
    });
    expect(res.status).toBe(400);
  });

  it("stores a memory item and finds it by search", async () => {
    const hub = await createHub();
    const postRes = await SELF.fetch("https://jarvis.test/v1/memory", {
      method: "POST",
      headers: auth(hub.token),
      body: JSON.stringify({ kind: "preference", content: "يفضل الواجهة الداكنة", tags: "ui" }),
    });
    expect(postRes.status).toBe(201);

    const searchRes = await SELF.fetch("https://jarvis.test/v1/memory?q=الداكنة", {
      headers: auth(hub.token),
    });
    const body = (await searchRes.json()) as { items: { content: string }[] };
    expect(body.items).toHaveLength(1);
    expect(body.items[0].content).toBe("يفضل الواجهة الداكنة");
  });
});

describe("AI relay", () => {
  it("refuses a request with no provider key", async () => {
    const hub = await createHub();
    const res = await SELF.fetch("https://jarvis.test/v1/ai/chat/completions", {
      method: "POST",
      headers: auth(hub.token),
      body: JSON.stringify({ model: "x", messages: [] }),
    });
    expect(res.status).toBe(400);
    const body = (await res.json()) as { error: { code: string } };
    expect(body.error.code).toBe("missing_provider_key");
  });

  it("refuses a malformed provider key", async () => {
    const hub = await createHub();
    const res = await SELF.fetch("https://jarvis.test/v1/ai/chat/completions", {
      method: "POST",
      headers: { ...auth(hub.token), "x-openrouter-key": "not-a-key" },
      body: JSON.stringify({ model: "x", messages: [] }),
    });
    expect(res.status).toBe(400);
  });

  it("refuses an unauthenticated relay call", async () => {
    const res = await SELF.fetch("https://jarvis.test/v1/ai/chat/completions", {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ model: "x", messages: [] }),
    });
    expect(res.status).toBe(401);
  });
});

describe("voice relay", () => {
  it("reports that no voice engine is configured", async () => {
    const hub = await createHub();
    const res = await SELF.fetch("https://jarvis.test/v1/voice/tts", {
      method: "POST",
      headers: auth(hub.token),
      body: JSON.stringify({ text: "مرحبا" }),
    });
    // No VOICE binding and no VOICE_URL in the test config.
    expect(res.status).toBe(503);
    const body = (await res.json()) as { error: { code: string } };
    expect(body.error.code).toBe("voice_not_configured");
  });

  it("rejects an unknown voice route", async () => {
    const hub = await createHub();
    const res = await SELF.fetch("https://jarvis.test/v1/voice/sing", {
      method: "POST",
      headers: auth(hub.token),
      body: JSON.stringify({ text: "x" }),
    });
    expect(res.status).toBe(404);
  });
});

describe("abuse limits", () => {
  it("stops hub creation after 5 attempts from one address", async () => {
    const ip = { "cf-connecting-ip": "198.51.100.77" };
    const attempt = () =>
      SELF.fetch("https://jarvis.test/v1/hubs", {
        method: "POST",
        headers: { ...JSON_HEADERS, ...ip },
        body: JSON.stringify({ deviceName: "Flood", platform: "other" }),
      });

    for (let i = 0; i < 5; i++) {
      expect((await attempt()).status).toBe(201);
    }
    const blocked = await attempt();
    expect(blocked.status).toBe(429);
    const body = (await blocked.json()) as { error: { code: string; retryAfterSeconds?: number } };
    expect(body.error.code).toBe("rate_limited");
    expect(body.error.retryAfterSeconds).toBeGreaterThan(0);
  });

  it("locks pairing after 5 bad codes", async () => {
    const phone = await createHub("Phone", "android");
    const startRes = await SELF.fetch("https://jarvis.test/v1/pair/start", {
      method: "POST",
      headers: auth(phone.token),
    });
    const { code } = (await startRes.json()) as { code: string };
    const hubId = code.split("-")[0];

    const guess = (digits: string) =>
      SELF.fetch("https://jarvis.test/v1/pair/complete", {
        method: "POST",
        headers: { ...JSON_HEADERS, "cf-connecting-ip": "198.51.100.9" },
        body: JSON.stringify({ code: `${hubId}-${digits}`, deviceName: "Guess", platform: "other" }),
      });

    for (let i = 0; i < 5; i++) {
      expect((await guess("000000")).status).toBe(401);
    }
    const locked = await guess("000000");
    expect(locked.status).toBe(429);
    const body = (await locked.json()) as { error: { code: string } };
    expect(body.error.code).toBe("pairing_locked");

    // Even the real code is refused while the lock holds.
    const realCode = await SELF.fetch("https://jarvis.test/v1/pair/complete", {
      method: "POST",
      headers: { ...JSON_HEADERS, "cf-connecting-ip": "198.51.100.10" },
      body: JSON.stringify({ code, deviceName: "Desktop", platform: "windows" }),
    });
    expect(realCode.status).toBe(429);
  });
});

describe("unknown routes", () => {
  it("404s outside /v1", async () => {
    const res = await SELF.fetch("https://jarvis.test/admin");
    expect(res.status).toBe(404);
  });
});
