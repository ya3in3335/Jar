import { DurableObject } from "cloudflare:workers";
import type { Env } from "./env";
import {
  HttpError,
  PAIR_TTL_MS,
  TOKEN_TTL_MS,
  bearer,
  capabilities,
  errorResponse,
  json,
  parseToken,
  platform,
  randomDigits,
  randomId,
  randomSecret,
  readJson,
  sha256Hex,
  str,
  timingSafeEqual,
} from "./util";

const LIMITS = {
  perMinute: 120,
  aiPerMinute: 30,
  voicePerMinute: 90,
  tasksPerMinute: 20,
  pairFailWindowMs: 10 * 60 * 1000,
  pairFailMax: 5,
} as const;

const MAX_DEVICES = 10;
const MAX_CONVERSATIONS = 200;
const MAX_MEMORY = 2000;
const TASK_RETENTION_MS = 7 * 24 * 3600 * 1000;
const AUDIT_RETENTION_MS = 30 * 24 * 3600 * 1000;

interface DeviceRow {
  id: string;
  name: string;
  platform: string;
  capabilities: string;
  token_hash: string;
  created: number;
  last_seen: number;
  revoked: number;
  expires: number;
}

interface TaskRow {
  id: string;
  origin: string;
  target: string;
  kind: string;
  payload: string;
  status: string;
  result: string | null;
  created: number;
  updated: number;
}

/**
 * One Durable Object per hub — a hub is "one person's set of devices".
 *
 * Holds all hub state in the DO's embedded SQLite, and all live device
 * sockets. WebSockets are accepted with hibernation, so an idle hub costs
 * nothing while staying connected.
 */
export class Hub extends DurableObject<Env> {
  private sql: SqlStorage;
  /** In-memory per-minute counters; reset on cold start by design. */
  private rl = new Map<string, { windowStart: number; count: number }>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
      CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY, name TEXT, platform TEXT, capabilities TEXT,
        token_hash TEXT, created INTEGER, last_seen INTEGER,
        revoked INTEGER DEFAULT 0, expires INTEGER);
      CREATE TABLE IF NOT EXISTS pair_codes (
        code_hash TEXT PRIMARY KEY, expires INTEGER, created_by TEXT);
      CREATE TABLE IF NOT EXISTS pair_fail (ts INTEGER);
      CREATE TABLE IF NOT EXISTS tasks (
        id TEXT PRIMARY KEY, origin TEXT, target TEXT, kind TEXT, payload TEXT,
        status TEXT, result TEXT, created INTEGER, updated INTEGER);
      CREATE TABLE IF NOT EXISTS conversations (
        id TEXT PRIMARY KEY, title TEXT, device TEXT, updated INTEGER, messages TEXT);
      CREATE TABLE IF NOT EXISTS memory (
        id TEXT PRIMARY KEY, kind TEXT, content TEXT, tags TEXT, device TEXT, created INTEGER);
      CREATE TABLE IF NOT EXISTS audit (ts INTEGER, device TEXT, action TEXT);
    `);
  }

  // ------------------------------------------------------------- small helpers

  private hubId(): string | null {
    const rows = this.sql.exec("SELECT v FROM meta WHERE k='hub_id'").toArray();
    return rows.length ? String(rows[0].v) : null;
  }

  private audit(device: string, action: string): void {
    this.sql.exec("INSERT INTO audit (ts, device, action) VALUES (?, ?, ?)", Date.now(), device, action.slice(0, 200));
    this.sql.exec("DELETE FROM audit WHERE ts < ?", Date.now() - AUDIT_RETENTION_MS);
  }

  private limit(key: string, max: number): void {
    const now = Date.now();
    const entry = this.rl.get(key);
    if (!entry || now - entry.windowStart > 60_000) {
      this.rl.set(key, { windowStart: now, count: 1 });
      return;
    }
    if (++entry.count > max) {
      throw new HttpError(429, "rate_limited", "Too many requests", {
        retryAfterSeconds: Math.ceil((60_000 - (now - entry.windowStart)) / 1000),
      });
    }
  }

  private online(deviceId: string): boolean {
    return this.ctx.getWebSockets(deviceId).length > 0;
  }

  /** Push a message to every live socket of a device. Returns delivery count. */
  private send(deviceId: string, msg: unknown): number {
    let delivered = 0;
    const text = JSON.stringify(msg);
    for (const ws of this.ctx.getWebSockets(deviceId)) {
      try {
        ws.send(text);
        delivered++;
      } catch {
        // socket already gone; presence handler will clean up
      }
    }
    return delivered;
  }

  private publicDevice(d: DeviceRow) {
    return {
      id: d.id,
      name: d.name,
      platform: d.platform,
      capabilities: JSON.parse(d.capabilities || "[]"),
      created: d.created,
      lastSeen: d.last_seen,
      online: this.online(d.id),
      tokenExpires: d.expires,
    };
  }

  private listDevices() {
    return (this.sql.exec("SELECT * FROM devices WHERE revoked=0 ORDER BY created").toArray() as unknown as DeviceRow[])
      .map((d) => this.publicDevice(d));
  }

  private taskOut(t: TaskRow) {
    return {
      id: t.id,
      origin: t.origin,
      target: t.target,
      kind: t.kind,
      payload: JSON.parse(t.payload),
      status: t.status,
      result: t.result ? JSON.parse(t.result) : null,
      created: t.created,
      updated: t.updated,
    };
  }

  /** Mint a device + its token. The secret is returned once and only hashed here. */
  private async issueDevice(hubId: string, name: string, plat: string, caps: string[]) {
    const active = this.sql.exec("SELECT COUNT(*) AS c FROM devices WHERE revoked=0").one().c as number;
    if (active >= MAX_DEVICES) {
      throw new HttpError(409, "device_limit", `A hub can have at most ${MAX_DEVICES} devices`);
    }
    const id = randomId(10);
    const secret = randomSecret();
    const now = Date.now();
    const expires = now + TOKEN_TTL_MS;

    this.sql.exec(
      `INSERT INTO devices (id, name, platform, capabilities, token_hash, created, last_seen, revoked, expires)
       VALUES (?,?,?,?,?,?,?,0,?)`,
      id,
      name,
      plat,
      JSON.stringify(caps),
      await sha256Hex(secret),
      now,
      now,
      expires,
    );
    this.audit(id, `device_registered:${plat}`);
    return { hubId, deviceId: id, token: `jv1.${hubId}.${id}.${secret}`, tokenExpires: expires };
  }

  /** Authenticate a request's device token, or throw 401. */
  private async auth(req: Request, tokenOverride?: string | null): Promise<DeviceRow> {
    const parsed = parseToken(tokenOverride ?? bearer(req));
    if (!parsed || parsed.hubId !== this.hubId()) {
      throw new HttpError(401, "unauthorized", "Missing or invalid device token");
    }
    const rows = this.sql.exec("SELECT * FROM devices WHERE id=?", parsed.deviceId).toArray() as unknown as DeviceRow[];
    const device = rows[0];
    if (!device || device.revoked) {
      throw new HttpError(401, "unauthorized", "Device is not registered or was revoked");
    }
    if (!timingSafeEqual(await sha256Hex(parsed.secret), device.token_hash)) {
      throw new HttpError(401, "unauthorized", "Missing or invalid device token");
    }
    if (Date.now() > device.expires) {
      throw new HttpError(401, "token_expired", "Device token expired; pair again");
    }
    this.limit(`dev:${device.id}`, LIMITS.perMinute);
    this.sql.exec("UPDATE devices SET last_seen=? WHERE id=?", Date.now(), device.id);
    return device;
  }

  /** Resolve a task target by id, exact name, platform, then name substring. */
  private findDevice(ref: string): DeviceRow | undefined {
    const all = this.sql.exec("SELECT * FROM devices WHERE revoked=0").toArray() as unknown as DeviceRow[];
    const r = ref.trim().toLowerCase();
    return (
      all.find((d) => d.id === r) ??
      all.find((d) => d.name.toLowerCase() === r) ??
      all.find((d) => d.platform === r) ??
      all.find((d) => d.name.toLowerCase().includes(r))
    );
  }

  // -------------------------------------------------------------------- entry

  async fetch(req: Request): Promise<Response> {
    const requestId = req.headers.get("x-request-id") ?? crypto.randomUUID();
    try {
      return await this.route(req, new URL(req.url));
    } catch (e) {
      return errorResponse(e, requestId);
    }
  }

  private async route(req: Request, url: URL): Promise<Response> {
    const p = url.pathname;
    const m = req.method;

    // --- hub creation: the only route valid before a hub exists -------------
    if (p === "/v1/hubs" && m === "POST") {
      if (this.hubId()) throw new HttpError(409, "hub_exists", "Hub already initialised");
      const hubId = req.headers.get("x-hub-id");
      if (!hubId) throw new HttpError(400, "validation_error", "Missing hub id");
      const body = await readJson(req);
      this.sql.exec("INSERT INTO meta (k, v) VALUES ('hub_id', ?), ('created', ?)", hubId, String(Date.now()));
      const out = await this.issueDevice(
        hubId,
        str(body.deviceName, "deviceName", { max: 60 }),
        platform(body.platform),
        capabilities(body.capabilities),
      );
      return json(out, 201);
    }

    if (!this.hubId()) throw new HttpError(404, "hub_not_found", "Unknown hub");

    // --- pairing: joins a second device, authenticated by the code only -----
    if (p === "/v1/pair/complete" && m === "POST") {
      const now = Date.now();
      this.sql.exec("DELETE FROM pair_fail WHERE ts < ?", now - LIMITS.pairFailWindowMs);
      const fails = this.sql.exec("SELECT COUNT(*) AS c FROM pair_fail").one().c as number;
      if (fails >= LIMITS.pairFailMax) {
        throw new HttpError(
          429,
          "pairing_locked",
          "Too many failed pairing attempts; wait 10 minutes and create a new code",
        );
      }
      const body = await readJson(req);
      const digits = String(body.code ?? "").split("-").pop() ?? "";
      const codeHash = await sha256Hex(`${this.hubId()}:${digits}`);

      this.sql.exec("DELETE FROM pair_codes WHERE expires < ?", now);
      const row = this.sql.exec("SELECT * FROM pair_codes WHERE code_hash=?", codeHash).toArray()[0];
      if (!row) {
        this.sql.exec("INSERT INTO pair_fail (ts) VALUES (?)", now);
        throw new HttpError(401, "invalid_pairing_code", "Pairing code is invalid or expired");
      }
      this.sql.exec("DELETE FROM pair_codes WHERE code_hash=?", codeHash); // single use

      const out = await this.issueDevice(
        this.hubId()!,
        str(body.deviceName, "deviceName", { max: 60 }),
        platform(body.platform),
        capabilities(body.capabilities),
      );
      for (const d of this.sql.exec("SELECT id FROM devices WHERE revoked=0").toArray()) {
        this.send(String(d.id), { type: "device_paired", deviceId: out.deviceId });
      }
      return json(out, 201);
    }

    // --- websocket: presence + task delivery --------------------------------
    if (p === "/v1/ws") {
      if ((req.headers.get("upgrade") ?? "").toLowerCase() !== "websocket") {
        throw new HttpError(426, "upgrade_required", "WebSocket upgrade required");
      }
      const device = await this.auth(req, bearer(req) ?? url.searchParams.get("token"));
      const pair = new WebSocketPair();
      // Tag the socket with the device id so getWebSockets(id) finds it.
      this.ctx.acceptWebSocket(pair[1], [device.id]);
      pair[1].serializeAttachment({ deviceId: device.id });
      pair[1].send(JSON.stringify({ type: "hello", deviceId: device.id, devices: this.listDevices() }));

      for (const other of this.sql
        .exec("SELECT id FROM devices WHERE revoked=0 AND id<>?", device.id)
        .toArray()) {
        this.send(String(other.id), { type: "presence", deviceId: device.id, online: true });
      }
      // Replay very recent undelivered work so a reconnect does not lose it.
      for (const t of this.sql
        .exec(
          "SELECT * FROM tasks WHERE target=? AND status='delivered' AND created > ?",
          device.id,
          Date.now() - 120_000,
        )
        .toArray() as unknown as TaskRow[]) {
        pair[1].send(JSON.stringify({ type: "task", task: this.taskOut(t) }));
      }
      return new Response(null, { status: 101, webSocket: pair[0] });
    }

    // ----- everything below requires a valid device token -------------------
    const dev = await this.auth(req);

    if (p === "/v1/me" && m === "GET") {
      return json({ hubId: this.hubId(), device: this.publicDevice(dev) });
    }

    if (p === "/v1/auth/refresh" && m === "POST") {
      const secret = randomSecret();
      const expires = Date.now() + TOKEN_TTL_MS;
      this.sql.exec("UPDATE devices SET token_hash=?, expires=? WHERE id=?", await sha256Hex(secret), expires, dev.id);
      this.audit(dev.id, "token_rotated");
      return json({ token: `jv1.${this.hubId()}.${dev.id}.${secret}`, tokenExpires: expires });
    }

    if (p === "/v1/pair/start" && m === "POST") {
      const digits = randomDigits(6);
      const expires = Date.now() + PAIR_TTL_MS;
      this.sql.exec("DELETE FROM pair_codes WHERE expires < ?", Date.now());
      this.sql.exec(
        "INSERT OR REPLACE INTO pair_codes (code_hash, expires, created_by) VALUES (?,?,?)",
        await sha256Hex(`${this.hubId()}:${digits}`),
        expires,
        dev.id,
      );
      this.audit(dev.id, "pair_code_created");
      return json({ code: `${this.hubId()}-${digits}`, expires, ttlSeconds: PAIR_TTL_MS / 1000 });
    }

    if (p === "/v1/devices" && m === "GET") {
      return json({ devices: this.listDevices(), self: dev.id });
    }

    const deviceMatch = /^\/v1\/devices\/([a-z2-9]{10})$/.exec(p);
    if (deviceMatch && m === "DELETE") {
      const target = deviceMatch[1];
      this.sql.exec("UPDATE devices SET revoked=1, token_hash='' WHERE id=?", target);
      for (const ws of this.ctx.getWebSockets(target)) {
        try {
          ws.close(4001, "revoked");
        } catch {
          /* already closed */
        }
      }
      this.audit(dev.id, `device_revoked:${target}`);
      return json({ ok: true });
    }

    if (deviceMatch && m === "PATCH") {
      if (deviceMatch[1] !== dev.id) {
        throw new HttpError(403, "forbidden", "Devices can only update themselves");
      }
      const body = await readJson(req);
      if (body.name !== undefined) {
        this.sql.exec("UPDATE devices SET name=? WHERE id=?", str(body.name, "name", { max: 60 }), dev.id);
      }
      if (body.capabilities !== undefined) {
        this.sql.exec(
          "UPDATE devices SET capabilities=? WHERE id=?",
          JSON.stringify(capabilities(body.capabilities)),
          dev.id,
        );
      }
      return json({ ok: true });
    }

    // --- cross-device tasks -------------------------------------------------
    if (p === "/v1/tasks" && m === "POST") {
      this.limit(`task:${dev.id}`, LIMITS.tasksPerMinute);
      const body = await readJson(req, 64 * 1024);
      const target = this.findDevice(str(body.target, "target", { max: 100 }));
      if (!target) throw new HttpError(404, "device_not_found", "No paired device matches 'target'");
      if (target.id === dev.id) {
        throw new HttpError(400, "validation_error", "Cannot route a task to the same device");
      }
      if (!this.online(target.id)) {
        throw new HttpError(409, "device_offline", `${target.name} is offline`, {
          device: this.publicDevice(target),
        });
      }

      const kind = body.kind === "open_conversation" ? "open_conversation" : "instruction";
      const payload =
        kind === "instruction"
          ? { instruction: str(body.instruction, "instruction", { max: 2000 }) }
          : { conversationId: str(body.conversationId, "conversationId", { max: 64 }) };

      const id = randomId(12);
      const now = Date.now();
      this.sql.exec(
        `INSERT INTO tasks (id, origin, target, kind, payload, status, result, created, updated)
         VALUES (?,?,?,?,?,'delivered',NULL,?,?)`,
        id,
        dev.id,
        target.id,
        kind,
        JSON.stringify(payload),
        now,
        now,
      );
      const row = this.sql.exec("SELECT * FROM tasks WHERE id=?", id).one() as unknown as TaskRow;
      if (!this.send(target.id, { type: "task", task: this.taskOut(row) })) {
        this.sql.exec(
          "UPDATE tasks SET status='failed', result=? WHERE id=?",
          JSON.stringify({ summary: "delivery failed" }),
          id,
        );
        throw new HttpError(409, "device_offline", `${target.name} disconnected before delivery`);
      }
      this.audit(dev.id, `task_sent:${kind}:${target.id}`);
      this.sql.exec("DELETE FROM tasks WHERE created < ?", now - TASK_RETENTION_MS);
      return json({ task: this.taskOut(row) }, 202);
    }

    const taskMatch = /^\/v1\/tasks\/([a-z2-9]{12})(\/events)?$/.exec(p);
    if (taskMatch && m === "GET" && !taskMatch[2]) {
      const t = this.sql.exec("SELECT * FROM tasks WHERE id=?", taskMatch[1]).toArray()[0] as unknown as TaskRow;
      if (!t || (t.origin !== dev.id && t.target !== dev.id)) {
        throw new HttpError(404, "task_not_found", "Unknown task");
      }
      return json({ task: this.taskOut(t) });
    }

    if (taskMatch && m === "POST" && taskMatch[2]) {
      const t = this.sql.exec("SELECT * FROM tasks WHERE id=?", taskMatch[1]).toArray()[0] as unknown as TaskRow;
      if (!t || t.target !== dev.id) throw new HttpError(404, "task_not_found", "Unknown task");
      const body = await readJson(req, 32 * 1024);
      const allowed = ["accepted", "running", "completed", "partial", "failed", "rejected"];
      if (typeof body.status !== "string" || !allowed.includes(body.status)) {
        throw new HttpError(400, "validation_error", `status must be one of ${allowed.join(", ")}`);
      }
      const result = {
        summary: str(body.summary, "summary", { max: 2000, optional: true }) ?? null,
        evidence: str(body.evidence, "evidence", { max: 2000, optional: true }) ?? null,
      };
      this.sql.exec(
        "UPDATE tasks SET status=?, result=?, updated=? WHERE id=?",
        body.status,
        JSON.stringify(result),
        Date.now(),
        taskMatch[1],
      );
      const out = this.taskOut(this.sql.exec("SELECT * FROM tasks WHERE id=?", taskMatch[1]).one() as unknown as TaskRow);
      this.send(t.origin, { type: "task_update", task: out });
      return json({ task: out });
    }

    // --- conversations ------------------------------------------------------
    if (p === "/v1/conversations" && m === "GET") {
      const rows = this.sql
        .exec(
          `SELECT id, title, device, updated, json_array_length(messages) AS n
           FROM conversations ORDER BY updated DESC LIMIT 50`,
        )
        .toArray();
      return json({
        conversations: rows.map((r) => ({
          id: r.id,
          title: r.title,
          device: r.device,
          updated: r.updated,
          messageCount: r.n,
        })),
      });
    }

    if (p === "/v1/conversations" && m === "DELETE") {
      this.sql.exec("DELETE FROM conversations");
      this.audit(dev.id, "conversations_cleared");
      return json({ ok: true });
    }

    const convMatch = /^\/v1\/conversations\/([A-Za-z0-9_-]{6,64})$/.exec(p);
    if (convMatch && m === "GET") {
      const r = this.sql.exec("SELECT * FROM conversations WHERE id=?", convMatch[1]).toArray()[0];
      if (!r) throw new HttpError(404, "conversation_not_found", "Unknown conversation");
      return json({
        id: r.id,
        title: r.title,
        device: r.device,
        updated: r.updated,
        messages: JSON.parse(String(r.messages)),
      });
    }

    if (convMatch && m === "PUT") {
      const body = await readJson(req, 512 * 1024);
      if (!Array.isArray(body.messages) || body.messages.length > 500) {
        throw new HttpError(400, "validation_error", "'messages' must be an array (max 500)");
      }
      const messages = (body.messages as unknown[]).map((raw, i) => {
        const x = raw as { role?: unknown; content?: unknown; ts?: unknown };
        if (
          !x ||
          !["user", "assistant", "system"].includes(x.role as string) ||
          typeof x.content !== "string"
        ) {
          throw new HttpError(400, "validation_error", `messages[${i}] invalid`);
        }
        return { role: x.role, content: x.content.slice(0, 20_000), ts: Number(x.ts) || Date.now() };
      });
      this.sql.exec(
        "INSERT OR REPLACE INTO conversations (id, title, device, updated, messages) VALUES (?,?,?,?,?)",
        convMatch[1],
        str(body.title, "title", { max: 120, optional: true }) ?? "Conversation",
        dev.id,
        Date.now(),
        JSON.stringify(messages),
      );
      this.sql.exec(
        `DELETE FROM conversations WHERE id NOT IN
         (SELECT id FROM conversations ORDER BY updated DESC LIMIT ${MAX_CONVERSATIONS})`,
      );
      return json({ ok: true, id: convMatch[1], messageCount: messages.length });
    }

    if (convMatch && m === "DELETE") {
      this.sql.exec("DELETE FROM conversations WHERE id=?", convMatch[1]);
      return json({ ok: true });
    }

    // --- long-term memory ---------------------------------------------------
    if (p === "/v1/memory" && m === "GET") {
      const q = (url.searchParams.get("q") ?? "").slice(0, 200);
      const rows = q
        ? this.sql
            .exec(
              "SELECT * FROM memory WHERE content LIKE ? OR tags LIKE ? ORDER BY created DESC LIMIT 50",
              `%${q}%`,
              `%${q}%`,
            )
            .toArray()
        : this.sql.exec("SELECT * FROM memory ORDER BY created DESC LIMIT 200").toArray();
      return json({ items: rows });
    }

    if (p === "/v1/memory" && m === "POST") {
      const body = await readJson(req, 16 * 1024);
      const kinds = ["fact", "preference", "workflow", "task_summary"];
      const kind = typeof body.kind === "string" && kinds.includes(body.kind) ? body.kind : "fact";
      const count = this.sql.exec("SELECT COUNT(*) AS c FROM memory").one().c as number;
      if (count >= MAX_MEMORY) {
        throw new HttpError(409, "memory_full", `Memory limit ${MAX_MEMORY} reached`);
      }
      const id =
        typeof body.id === "string" && /^[A-Za-z0-9_-]{6,64}$/.test(body.id) ? body.id : randomId(12);
      this.sql.exec(
        "INSERT OR REPLACE INTO memory (id, kind, content, tags, device, created) VALUES (?,?,?,?,?,?)",
        id,
        kind,
        str(body.content, "content", { max: 4000 }),
        str(body.tags, "tags", { max: 200, optional: true }) ?? "",
        dev.id,
        Number(body.created) || Date.now(),
      );
      return json({ ok: true, id }, 201);
    }

    if (p === "/v1/memory" && m === "DELETE") {
      this.sql.exec("DELETE FROM memory");
      this.audit(dev.id, "memory_cleared");
      return json({ ok: true });
    }

    const memMatch = /^\/v1\/memory\/([A-Za-z0-9_-]{6,64})$/.exec(p);
    if (memMatch && m === "DELETE") {
      this.sql.exec("DELETE FROM memory WHERE id=?", memMatch[1]);
      return json({ ok: true });
    }

    if (p === "/v1/audit" && m === "GET") {
      return json({ items: this.sql.exec("SELECT * FROM audit ORDER BY ts DESC LIMIT 200").toArray() });
    }

    /**
     * Internal: the stateless Worker calls this to authenticate a device and
     * charge its AI/voice quota before it proxies to an upstream provider.
     */
    if (p === "/v1/_relay_check" && m === "POST") {
      const kind = url.searchParams.get("kind") === "voice" ? "voice" : "ai";
      this.limit(`${kind}:${dev.id}`, kind === "voice" ? LIMITS.voicePerMinute : LIMITS.aiPerMinute);
      return json({ ok: true, deviceId: dev.id });
    }

    throw new HttpError(404, "not_found", "Route not found");
  }

  // ------------------------------------------- hibernatable socket callbacks

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const att = ws.deserializeAttachment() as { deviceId: string } | null;
    if (!att) return;
    if (typeof message !== "string" || message.length > 16_384) return;
    let msg: { type?: string };
    try {
      msg = JSON.parse(message);
    } catch {
      return;
    }
    if (msg?.type === "ping") {
      ws.send(JSON.stringify({ type: "pong", t: Date.now() }));
      this.sql.exec("UPDATE devices SET last_seen=? WHERE id=?", Date.now(), att.deviceId);
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    this.presenceChanged(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.presenceChanged(ws);
  }

  /** Announce offline only when a device's last socket is gone. */
  private presenceChanged(ws: WebSocket): void {
    const att = ws.deserializeAttachment() as { deviceId: string } | null;
    if (!att) return;
    this.sql.exec("UPDATE devices SET last_seen=? WHERE id=?", Date.now(), att.deviceId);
    const stillOpen = this.ctx
      .getWebSockets(att.deviceId)
      .some((w) => w !== ws && w.readyState === WebSocket.OPEN);
    if (stillOpen) return;
    for (const other of this.sql
      .exec("SELECT id FROM devices WHERE revoked=0 AND id<>?", att.deviceId)
      .toArray()) {
      this.send(String(other.id), { type: "presence", deviceId: att.deviceId, online: false });
    }
  }
}
