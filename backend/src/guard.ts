import { DurableObject } from "cloudflare:workers";
import { json } from "./util";

interface GuardRequest {
  key: string;
  max: number;
  windowMs: number;
}

/**
 * Sliding-window limiter for routes that have no device token yet
 * (hub creation, pairing). Keyed by hashed IP so no raw address is stored.
 *
 * In-memory only: a cold start resets counters. Acceptable, because this is
 * abuse damping rather than billing; per-device limits live in the Hub.
 */
export class Guard extends DurableObject {
  private hits = new Map<string, number[]>();

  async fetch(req: Request): Promise<Response> {
    const { key, max, windowMs } = (await req.json()) as GuardRequest;
    const now = Date.now();
    const recent = (this.hits.get(key) ?? []).filter((t) => now - t < windowMs);

    if (recent.length >= max) {
      const retryAfterSeconds = Math.ceil((windowMs - (now - recent[0])) / 1000);
      return json({ ok: false, retryAfterSeconds }, 429);
    }

    recent.push(now);
    this.hits.set(key, recent);
    if (this.hits.size > 5000) this.hits.clear(); // crude bound on memory
    return json({ ok: true });
  }
}
