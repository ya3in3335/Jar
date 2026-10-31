import type { Hub } from "./hub";
import type { Guard } from "./guard";

export interface Env {
  /** One Durable Object per hub: holds devices, tasks, conversations, memory. */
  HUB: DurableObjectNamespace<Hub>;
  /** Global IP-level rate limiter for unauthenticated routes. */
  GUARD: DurableObjectNamespace<Guard>;

  /** Comma-separated allowed CORS origins, or "*". Empty disables CORS. */
  ALLOWED_ORIGINS?: string;

  /**
   * Azure Speech credentials for the voice relay. Secret; set with
   * `wrangler secret put VOICE_API_KEY`. Without them the voice routes
   * answer 503 rather than pretending to work.
   */
  VOICE_API_KEY?: string;
  /** Azure region, e.g. "francecentral". */
  VOICE_REGION?: string;
  /**
   * Optional override for a self-hosted voice engine that accepts the same
   * JSON body and returns audio. Takes precedence over Azure when set.
   */
  VOICE_URL?: string;
}
