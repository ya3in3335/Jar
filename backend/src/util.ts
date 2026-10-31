/**
 * Shared primitives: errors, JSON responses, tokens, crypto, validation.
 */

export const VERSION = "1.0.0";

/** Device tokens live 90 days, then the device must pair again. */
export const TOKEN_TTL_MS = 90 * 24 * 3600 * 1000;
/** Pairing codes live 5 minutes. */
export const PAIR_TTL_MS = 5 * 60 * 1000;

export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly extra?: Record<string, unknown>,
  ) {
    super(message);
  }
}

export function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...headers,
    },
  });
}

export function errorResponse(e: unknown, requestId: string): Response {
  if (e instanceof HttpError) {
    return json(
      { error: { code: e.code, message: e.message, status: e.status, ...(e.extra ?? {}) }, requestId },
      e.status,
    );
  }
  // Never leak internals to the client; log a truncated line instead.
  console.log(
    JSON.stringify({
      level: "error",
      requestId,
      err: String((e as Error)?.message ?? e).slice(0, 200),
    }),
  );
  return json({ error: { code: "internal_error", message: "Internal error", status: 500 }, requestId }, 500);
}

// ---------------------------------------------------------------- identifiers

/** Unambiguous alphabet: no l/o/0/1, so codes can be read aloud. */
const ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

export function randomId(len: number): string {
  const bytes = crypto.getRandomValues(new Uint8Array(len));
  let out = "";
  for (const b of bytes) out += ALPHABET[b % ALPHABET.length];
  return out;
}

export function randomDigits(n: number): string {
  const vals = crypto.getRandomValues(new Uint32Array(n));
  return Array.from(vals, (v) => String(v % 10)).join("");
}

export function randomSecret(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

export async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time string compare, to keep token checks free of timing signal. */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// --------------------------------------------------------------------- tokens

export interface ParsedToken {
  hubId: string;
  deviceId: string;
  secret: string;
}

const TOKEN_RE = /^jv1\.([a-z2-9]{12})\.([a-z2-9]{10})\.([A-Za-z0-9_-]{40,60})$/;

export function parseToken(token: string | null | undefined): ParsedToken | null {
  if (!token) return null;
  const m = TOKEN_RE.exec(token.trim());
  return m ? { hubId: m[1], deviceId: m[2], secret: m[3] } : null;
}

export function bearer(req: Request): string | null {
  const header = req.headers.get("authorization") ?? "";
  const m = /^Bearer\s+(.+)$/i.exec(header);
  return m ? m[1].trim() : null;
}

// ----------------------------------------------------------------- validation

export async function readJson(
  req: Request,
  maxBytes = 256 * 1024,
): Promise<Record<string, unknown>> {
  const ct = (req.headers.get("content-type") ?? "").toLowerCase();
  if (!ct.includes("application/json")) {
    throw new HttpError(415, "unsupported_media_type", "Content-Type must be application/json");
  }
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (declared > maxBytes) {
    throw new HttpError(413, "payload_too_large", `Body exceeds ${maxBytes} bytes`);
  }
  const text = await req.text();
  if (text.length > maxBytes) {
    throw new HttpError(413, "payload_too_large", `Body exceeds ${maxBytes} bytes`);
  }
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new HttpError(400, "invalid_json", "Body must be a JSON object");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new HttpError(400, "invalid_json", "Body must be a JSON object");
  }
  return value as Record<string, unknown>;
}

interface StrOpts {
  min?: number;
  max?: number;
  optional?: boolean;
}

export function str(value: unknown, name: string, opts: StrOpts = {}): string {
  const { min = 1, max = 200, optional = false } = opts;
  if (value === undefined || value === null || value === "") {
    if (optional) return undefined as unknown as string;
    throw new HttpError(400, "validation_error", `'${name}' is required`);
  }
  if (typeof value !== "string") {
    throw new HttpError(400, "validation_error", `'${name}' must be a string`);
  }
  const trimmed = value.trim();
  const len = [...trimmed].length; // count code points, not UTF-16 units
  if (len < min || len > max) {
    throw new HttpError(400, "validation_error", `'${name}' length must be ${min}-${max}`);
  }
  return trimmed;
}

export const PLATFORMS = ["android", "windows", "web", "other"] as const;
export type Platform = (typeof PLATFORMS)[number];

export function platform(value: unknown): Platform {
  if (typeof value !== "string" || !(PLATFORMS as readonly string[]).includes(value)) {
    throw new HttpError(400, "validation_error", `'platform' must be one of ${PLATFORMS.join(", ")}`);
  }
  return value as Platform;
}

/** Tool names a device advertises; keeps the hub's view of capabilities bounded. */
export function capabilities(value: unknown): string[] {
  if (value === undefined) return [];
  const ok =
    Array.isArray(value) &&
    value.length <= 100 &&
    value.every((x) => typeof x === "string" && x.length <= 60 && /^[a-z0-9_]+$/.test(x));
  if (!ok) {
    throw new HttpError(
      400,
      "validation_error",
      "'capabilities' must be an array of up to 100 tool names (lowercase, digits, underscore)",
    );
  }
  return value as string[];
}
