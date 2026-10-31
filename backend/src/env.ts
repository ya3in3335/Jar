import type { Hub } from "./hub";
import type { Guard } from "./guard";

export interface Env {
  /** One Durable Object per hub: holds devices, tasks, conversations, memory. */
  HUB: DurableObjectNamespace<Hub>;
  /** Global IP-level rate limiter for unauthenticated routes. */
  GUARD: DurableObjectNamespace<Guard>;

  /** Comma-separated allowed CORS origins, or "*". Empty disables CORS. */
  ALLOWED_ORIGINS?: string;

  /** Optional voice engine: service binding preferred, URL as fallback. */
  VOICE?: Fetcher;
  VOICE_URL?: string;
  /** Secret. Sent to the voice engine as x-api-key. */
  VOICE_API_KEY?: string;
}
