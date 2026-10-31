import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-plugin";

/**
 * Tests run inside workerd (via Miniflare), not Node, so Durable Objects,
 * ctx.storage.sql and WebSockets behave as they do in production.
 */
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
    }),
  ],
});
