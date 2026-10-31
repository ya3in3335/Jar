/**
 * JARVIS cloud backend — stateless edge entry point.
 *
 * Responsibilities kept here:
 *   - CORS, request ids, uniform error shape
 *   - IP-level damping on unauthenticated routes
 *   - routing a device token to its hub's Durable Object
 *   - proxying AI (OpenRouter, bring-your-own-key) and voice upstreams
 *
 * All hub state lives in the Hub Durable Object (see hub.ts).
 */
import { Hub } from "./hub";
import { Guard } from "./guard";
import type { Env } from "./env";
import {
  ARABIC_MALE_VOICES,
  VOICE_DEFAULTS,
  VOICE_LIMITS,
  VOICE_STYLES,
  azureEndpoint,
  buildSsml,
  mapAzureError,
  parseVoiceRequest,
} from "./voice";
import { HttpError, VERSION, bearer, errorResponse, json, parseToken, randomId, readJson } from "./util";

export { Hub, Guard };

const OPENROUTER = "https://openrouter.ai/api/v1";

function corsHeaders(req: Request, env: Env): Record<string, string> {
  const origin = req.headers.get("origin");
  const allowed = (env.ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (!origin || !(allowed.includes("*") || allowed.includes(origin))) return {};
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
    "access-control-allow-headers": "Authorization, Content-Type, X-OpenRouter-Key, X-Request-Id",
    "access-control-max-age": "600",
    vary: "Origin",
  };
}

async function guard(env: Env, key: string, max: number, windowMs: number): Promise<void> {
  const stub = env.GUARD.get(env.GUARD.idFromName("global"));
  const res = await stub.fetch("https://guard/", {
    method: "POST",
    body: JSON.stringify({ key, max, windowMs }),
  });
  if (res.status === 429) {
    const body = (await res.json()) as { retryAfterSeconds?: number };
    throw new HttpError(429, "rate_limited", "Too many requests from this network", {
      retryAfterSeconds: body.retryAfterSeconds,
    });
  }
}

function hubStub(env: Env, hubId: string) {
  return env.HUB.get(env.HUB.idFromName(hubId));
}

/** Hash the caller IP so rate-limit keys never hold a raw address. */
async function ipKey(req: Request): Promise<string> {
  const ip = req.headers.get("cf-connecting-ip") ?? "unknown";
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("jarvis-ip:" + ip));
  return [...new Uint8Array(d)].slice(0, 12).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Authenticate a relay call against the device's hub and charge its quota.
 * Returns the device id. Throws the hub's own error if it refuses.
 */
async function relayAuth(req: Request, env: Env, kind: "ai" | "voice" = "ai"): Promise<string> {
  const token = bearer(req);
  const parsed = parseToken(token);
  if (!parsed) throw new HttpError(401, "unauthorized", "Missing or invalid device token");

  const res = await hubStub(env, parsed.hubId).fetch(`https://hub/v1/_relay_check?kind=${kind}`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as {
      error?: { code?: string; message?: string; retryAfterSeconds?: number };
    };
    throw new HttpError(
      res.status,
      body.error?.code ?? "unauthorized",
      body.error?.message ?? "Unauthorized",
      body.error?.retryAfterSeconds ? { retryAfterSeconds: body.error.retryAfterSeconds } : undefined,
    );
  }
  return parsed.deviceId;
}

const DOCS = `JARVIS cloud backend ${VERSION}

Every /v1 route except POST /v1/hubs, POST /v1/pair/complete and GET /health needs:
  Authorization: Bearer jv1.<hub>.<device>.<secret>

  POST   /v1/hubs                   create a hub + its first device
  POST   /v1/pair/start             (auth) one-time 6-digit code, 5 min TTL
  POST   /v1/pair/complete          join a hub with {code, deviceName, platform}
  GET    /v1/me
  GET    /v1/devices
  PATCH  /v1/devices/:id            rename / update own capabilities
  DELETE /v1/devices/:id            revoke a device
  POST   /v1/auth/refresh           rotate this device's token
  GET    /v1/ws                     WebSocket: presence + task delivery
  POST   /v1/tasks                  {target, kind, instruction|conversationId}
  GET    /v1/tasks/:id
  POST   /v1/tasks/:id/events       {status, summary?, evidence?}
  GET    /v1/conversations          DELETE to clear all
  GET|PUT|DELETE /v1/conversations/:id
  GET|POST|DELETE /v1/memory        DELETE /v1/memory/:id
  GET    /v1/audit
  POST   /v1/ai/chat/completions    relay; X-OpenRouter-Key is forwarded, never stored
  POST   /v1/voice/tts              Arabic speech, returns audio/mpeg
  GET    /v1/voice/voices           voice catalogue + defaults
`;

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const requestId = crypto.randomUUID();
    const url = new URL(req.url);
    const cors = corsHeaders(req, env);

    const withCors = (res: Response): Response => {
      if (res.status === 101) return res; // never touch a websocket handshake
      const headers = new Headers(res.headers);
      for (const [k, v] of Object.entries(cors)) headers.set(k, v);
      headers.set("x-request-id", requestId);
      headers.set("x-content-type-options", "nosniff");
      return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
    };

    if (req.method === "OPTIONS") return withCors(new Response(null, { status: 204 }));

    try {
      const p = url.pathname;

      if (p === "/" || p === "/docs") {
        return withCors(new Response(DOCS, { headers: { "content-type": "text/plain; charset=utf-8" } }));
      }

      if (p === "/health") {
        return withCors(
          json({
            ok: true,
            service: "jarvis-backend",
            version: VERSION,
            time: new Date().toISOString(),
            voiceConfigured: Boolean(env.VOICE_URL || (env.VOICE_API_KEY && env.VOICE_REGION)),
          }),
        );
      }

      // --- hub creation: unauthenticated, so damp it hard ------------------
      if (p === "/v1/hubs" && req.method === "POST") {
        const key = await ipKey(req);
        await guard(env, `hub:${key}`, 5, 3_600_000);
        await guard(env, "hub:global", 200, 24 * 3_600_000);

        // Validate shape at the edge so a non-JSON body gets 415, not a
        // confusing parse error from inside the hub.
        const body = await readJson(req, 4096);
        const hubId = randomId(12);
        const forwarded = new Request("https://hub/v1/hubs", {
          method: "POST",
          headers: { "content-type": "application/json", "x-hub-id": hubId },
          body: JSON.stringify(body),
        });
        return withCors(await hubStub(env, hubId).fetch(forwarded));
      }

      // --- pairing completion: the code is the only credential ------------
      if (p === "/v1/pair/complete" && req.method === "POST") {
        await guard(env, `pair:${await ipKey(req)}`, 20, 3_600_000);
        const body = await readJson(req, 4096);
        const m = /^([a-z2-9]{12})-(\d{6})$/.exec(String(body.code ?? "").trim().toLowerCase());
        if (!m) {
          throw new HttpError(400, "validation_error", "code must look like <12-char hub id>-<6 digits>");
        }
        return withCors(
          await hubStub(env, m[1]).fetch(
            new Request("https://hub/v1/pair/complete", {
              method: "POST",
              headers: { "content-type": "application/json" },
              body: JSON.stringify(body),
            }),
          ),
        );
      }

      // --- voice: catalogue (no upstream call, no credentials needed) ------
      if (p === "/v1/voice/voices" && req.method === "GET") {
        await relayAuth(req, env, "voice");
        return withCors(
          json({
            voices: ARABIC_MALE_VOICES,
            default: VOICE_DEFAULTS.voice,
            defaults: { pitch: VOICE_DEFAULTS.pitch, rate: VOICE_DEFAULTS.rate },
            styles: VOICE_STYLES,
            // Stated plainly so no client advertises emotional synthesis.
            styleSupport: "prosody-only",
            configured: Boolean(env.VOICE_URL || (env.VOICE_API_KEY && env.VOICE_REGION)),
          }),
        );
      }

      // --- voice: synthesis ------------------------------------------------
      if (p === "/v1/voice/tts") {
        if (req.method !== "POST") throw new HttpError(405, "method_not_allowed", "Use POST");
        await relayAuth(req, env, "voice");

        const selfHosted = Boolean(env.VOICE_URL);
        if (!selfHosted && !(env.VOICE_API_KEY && env.VOICE_REGION)) {
          throw new HttpError(
            503,
            "voice_not_configured",
            "Set VOICE_API_KEY and VOICE_REGION (Azure Speech), or VOICE_URL for a self-hosted engine",
          );
        }

        // Validated at the edge so malformed input never reaches an upstream.
        const v = parseVoiceRequest(await readJson(req, VOICE_LIMITS.maxBodyBytes));

        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), VOICE_LIMITS.upstreamTimeoutMs);

        let upstream: Response;
        try {
          if (selfHosted) {
            upstream = await fetch(env.VOICE_URL!, {
              method: "POST",
              headers: { "content-type": "application/json" },
              body: JSON.stringify({ text: v.text, voice: v.voice, pitch: v.pitch, rate: v.rate, style: v.style }),
              signal: controller.signal,
            });
          } else {
            upstream = await fetch(azureEndpoint(env.VOICE_REGION!), {
              method: "POST",
              headers: {
                "ocp-apim-subscription-key": env.VOICE_API_KEY!,
                "content-type": "application/ssml+xml",
                "x-microsoft-outputformat": "audio-24khz-48kbitrate-mono-mp3",
                "user-agent": "jarvis",
              },
              body: buildSsml(v),
              signal: controller.signal,
            });
          }
        } catch {
          throw new HttpError(504, "voice_timeout", "The voice service did not respond in time");
        } finally {
          clearTimeout(timer);
        }

        if (!upstream.ok || !upstream.body) {
          throw mapAzureError(upstream.status);
        }

        return withCors(
          new Response(upstream.body, {
            status: 200,
            headers: {
              "content-type": "audio/mpeg",
              "cache-control": "no-store",
              "x-voice": v.voice,
              "x-voice-style": v.style,
              // Be explicit: styles map to prosody, not expressive synthesis.
              "x-voice-style-applied": "prosody-only",
            },
          }),
        );
      }

      // --- AI relay (bring your own OpenRouter key) ------------------------
      if (p === "/v1/ai/chat/completions" && req.method === "POST") {
        await relayAuth(req, env, "ai");

        const key = req.headers.get("x-openrouter-key");
        if (!key || !/^sk-or-[A-Za-z0-9_-]{10,200}$/.test(key)) {
          throw new HttpError(
            400,
            "missing_provider_key",
            "X-OpenRouter-Key header with an OpenRouter key is required",
          );
        }
        const declared = Number(req.headers.get("content-length") ?? 0);
        if (declared > 8 * 1024 * 1024) {
          throw new HttpError(413, "payload_too_large", "Request too large (8 MB max)");
        }

        const upstream = await fetch(`${OPENROUTER}/chat/completions`, {
          method: "POST",
          headers: {
            authorization: `Bearer ${key}`, // forwarded, never stored or logged
            "content-type": "application/json",
            "x-title": "JARVIS",
          },
          body: req.body,
        });
        return withCors(
          new Response(upstream.body, {
            status: upstream.status,
            headers: {
              "content-type": upstream.headers.get("content-type") ?? "application/json",
              "cache-control": "no-store",
            },
          }),
        );
      }

      // --- everything else: route the token to its hub ---------------------
      if (p.startsWith("/v1/")) {
        let token = bearer(req);
        if (!token && p === "/v1/ws") token = url.searchParams.get("token");
        const parsed = parseToken(token);
        if (!parsed) throw new HttpError(401, "unauthorized", "Missing or invalid device token");
        return withCors(await hubStub(env, parsed.hubId).fetch(req));
      }

      throw new HttpError(404, "not_found", "Route not found");
    } catch (e) {
      return withCors(errorResponse(e, requestId));
    }
  },
} satisfies ExportedHandler<Env>;
