// Mock SimpleX world for testing the Toasty UI without a phone.
//
// Uses the official SimpleX CLI (same version as the app core) to build an app profile "toasty"
// with contacts and a group, then keeps the contacts running so they can message the app live.
// Messages travel through the public SimpleX relays, like any SimpleX chat.
//
//   bun scripts/toasty/mock.ts setup          build the mock world (deletes a previous one)
//   bun scripts/toasty/mock.ts serve          run the contacts so they can talk to the app
//   bun scripts/toasty/mock.ts say alice hi   alice sends "hi" (needs `serve` running)
//   bun scripts/toasty/mock.ts say team:bob hi bob writes "hi" in the team group
//   bun scripts/toasty/mock.ts chatter        contacts send random messages every few seconds
//
// Run the app on the mock profile with APPDATA=%LOCALAPPDATA%\ToastyDev\Mock\AppData.

import { spawn, type Subprocess } from "bun"
import { existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs"
import { join } from "node:path"

const ROOT = join(process.env.LOCALAPPDATA ?? ".", "ToastyDev")
const CLI = join(ROOT, "bin", "simplex-chat.exe")
const MOCK = join(ROOT, "Mock")
const APP_DIR = join(MOCK, "AppData", "SimpleX") // the app's data folder when APPDATA=MOCK\AppData
const ME = { name: "toasty", port: 5300, db: join(APP_DIR, "simplex_v1") }
const BOTS = [
  { name: "alice", port: 5301 },
  { name: "bob", port: 5302 },
  { name: "carol", port: 5303 },
].map((b) => ({ ...b, db: join(MOCK, "bots", b.name, "simplex_v1") }))
const GROUP = "team"

// ---- CLI process + websocket API ----

class Chat {
  private ws!: WebSocket
  private nextId = 1
  private waiting = new Map<string, (r: any) => void>()
  private listeners: ((e: any) => void)[] = []

  constructor(readonly name: string, readonly port: number) {}

  async connect(timeoutMs = 30000) {
    const until = Date.now() + timeoutMs
    for (;;) {
      try {
        this.ws = await new Promise<WebSocket>((resolve, reject) => {
          const ws = new WebSocket(`ws://127.0.0.1:${this.port}`)
          ws.onopen = () => resolve(ws)
          ws.onerror = () => reject(new Error("ws error"))
        })
        break
      } catch (e) {
        if (Date.now() > until) throw new Error(`${this.name}: no chat server on port ${this.port}`)
        await Bun.sleep(500)
      }
    }
    this.ws.onmessage = (m) => {
      const msg = JSON.parse(String(m.data))
      const done = msg.corrId && this.waiting.get(msg.corrId)
      if (done) {
        this.waiting.delete(msg.corrId)
        done(msg.resp)
      } else {
        this.listeners.forEach((l) => l(msg.resp))
      }
    }
    return this
  }

  cmd(cmd: string): Promise<any> {
    const corrId = String(this.nextId++)
    return new Promise((resolve) => {
      this.waiting.set(corrId, resolve)
      this.ws.send(JSON.stringify({ corrId, cmd }))
    })
  }

  /** Resolves with the first event whose JSON contains `text`. */
  waitFor(text: string, timeoutMs = 60000): Promise<any> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${this.name}: timed out waiting for ${text}`)), timeoutMs)
      const l = (e: any) => {
        if (JSON.stringify(e).includes(text)) {
          clearTimeout(timer)
          this.listeners = this.listeners.filter((x) => x !== l)
          resolve(e)
        }
      }
      this.listeners.push(l)
    })
  }

  close() {
    this.ws?.close()
  }
}

function startCli(name: string, db: string, port: number, displayName?: string): Subprocess {
  mkdirSync(join(db, ".."), { recursive: true })
  const args = [CLI, "-d", db, "-p", String(port), "-y"]
  if (displayName) args.push("--user-display-name", displayName)
  const proc = spawn(args, { stdin: "pipe", stdout: "ignore", stderr: "pipe" })
  proc.exited.then((code) => {
    // 143 is our own kill when we are done with it
    if (code !== 0 && code !== 143 && code !== null) console.error(`${name}: CLI exited with ${code}`)
  })
  return proc
}

function linksIn(x: any): string[] {
  return JSON.stringify(x).match(/"(https:\/\/[^"]+|simplex:\/[^"]+)"/g)?.map((s) => JSON.parse(s)) ?? []
}

async function connectPair(me: Chat, bot: Chat) {
  const inv = await me.cmd("/c")
  const link = linksIn(inv).find((s) => s.includes("invitation") || s.includes("/i#"))
  if (!link) throw new Error(`no invitation link in ${JSON.stringify(inv).slice(0, 400)}`)
  const connected = Promise.all([me.waitFor("contactConnected"), bot.waitFor("contactConnected")])
  await bot.cmd(`/c ${link}`)
  await connected
  console.log(`  ${bot.name} ↔ ${ME.name} connected`)
}

// ---- seed conversations ----

const ALICE_CHAT: [string, string][] = [
  ["alice", "hey! did you try the new build?"],
  ["me", "yeah, running it now on the desktop"],
  ["alice", "how does the vim mode feel"],
  ["me", "j and k already feel natural, still learning the rest"],
  ["alice", "nice. remember to try gg and G on a long chat"],
  ["alice", "this message is intentionally a bit longer so it wraps over a couple of lines in a narrow pane, which is exactly what you want to see when testing how the layout reflows"],
  ["me", "ha, thanks"],
  ["alice", "link test: https://simplex.chat"],
  ["alice", "*bold*, _italic_ and `code` formatting"],
  ["me", "rendering fine 👍"],
]

async function seed(me: Chat, bots: Record<string, Chat>) {
  const send = async (from: string, text: string, to = from) => {
    if (from === "me") await me.cmd(`@${to} ${text}`)
    else await bots[from]!.cmd(`@${ME.name} ${text}`)
    await Bun.sleep(150)
  }
  for (const [who, text] of ALICE_CHAT) await send(who, text, "alice")
  for (let i = 1; i <= 40; i++) await send(i % 3 ? "bob" : "me", `bob chat line ${i}${i % 7 === 0 ? " — a longer one to vary heights a little" : ""}`, "bob")
  await send("carol", "hi, I'm carol. I'm mostly in the team group")

  await me.cmd(`/g ${GROUP}`)
  for (const b of Object.values(bots)) {
    const invited = b.waitFor("receivedGroupInvitation")
    await me.cmd(`/a ${GROUP} ${b.name}`)
    await invited
    const joined = me.waitFor("joinedGroupMember")
    await b.cmd(`/j ${GROUP}`)
    await joined.catch(() => {})
    console.log(`  ${b.name} joined #${GROUP}`)
  }
  await Bun.sleep(2000)
  const lines: [string, string][] = [
    ["me", "welcome to the team group"],
    ["alice", "hello all"],
    ["bob", "o/"],
    ["carol", "hey!"],
    ["alice", "standup in 5?"],
    ["bob", "sure"],
  ]
  for (const [who, text] of lines) {
    if (who === "me") await me.cmd(`#${GROUP} ${text}`)
    else await bots[who]!.cmd(`#${GROUP} ${text}`)
    await Bun.sleep(300)
  }
}

// ---- commands ----

async function setup() {
  if (!existsSync(CLI)) throw new Error(`SimpleX CLI not found at ${CLI}`)
  rmSync(MOCK, { recursive: true, force: true })
  console.log("starting CLIs…")
  const procs = [startCli(ME.name, ME.db, ME.port, ME.name), ...BOTS.map((b) => startCli(b.name, b.db, b.port, b.name))]
  try {
    const me = await new Chat(ME.name, ME.port).connect()
    const bots = Object.fromEntries(await Promise.all(BOTS.map(async (b) => [b.name, await new Chat(b.name, b.port).connect()] as const)))
    console.log("connecting contacts (via SimpleX relays)…")
    for (const b of Object.values(bots)) await connectPair(me, b)
    console.log("seeding messages…")
    await seed(me, bots)
    await Bun.sleep(3000) // let the last deliveries land
    me.close()
    Object.values(bots).forEach((b) => b.close())
  } finally {
    procs.forEach((p) => p.kill())
    await Promise.all(procs.map((p) => p.exited))
  }
  // the app skips onboarding for this profile
  writeFileSync(join(APP_DIR, "settings.properties"), "OnboardingStage=OnboardingComplete\nNewDatabaseInitialized=true\n")
  console.log(`done. app data: ${APP_DIR}`)
}

async function serve() {
  console.log(`contacts online: ${BOTS.map((b) => `${b.name} :${b.port}`).join(", ")} · ctrl+c stops`)
  const procs = BOTS.map((b) => startCli(b.name, b.db, b.port))
  process.on("SIGINT", () => {
    procs.forEach((p) => p.kill())
    process.exit(0)
  })
  await Promise.all(procs.map((p) => p.exited))
}

async function say(target: string, text: string) {
  const [first, second] = target.split(":")
  const inGroup = second !== undefined
  const botName = inGroup ? second! : first!
  const bot = BOTS.find((b) => b.name === botName)
  if (!bot) throw new Error(`unknown contact ${botName}; try ${BOTS.map((b) => b.name).join(", ")}`)
  const chat = await new Chat(bot.name, bot.port).connect(3000)
  const resp = await chat.cmd(inGroup ? `#${first} ${text}` : `@${ME.name} ${text}`)
  chat.close()
  const type = resp?.type ?? Object.keys(resp ?? {})[0]
  console.log(`${botName}${inGroup ? ` in #${first}` : ""}: ${text}  (${type})`)
}

async function chatter() {
  const words = "ok sure nice lol hmm yes no maybe later now cool wow great thanks why how what when".split(" ")
  const chats = await Promise.all(BOTS.map((b) => new Chat(b.name, b.port).connect(3000)))
  console.log("chatter on · ctrl+c stops")
  for (;;) {
    const c = chats[Math.floor(Math.random() * chats.length)]!
    const n = 1 + Math.floor(Math.random() * 12)
    const text = Array.from({ length: n }, () => words[Math.floor(Math.random() * words.length)]).join(" ")
    const group = Math.random() < 0.3
    await c.cmd(group ? `#${GROUP} ${text}` : `@${ME.name} ${text}`)
    console.log(`${c.name}${group ? ` #${GROUP}` : ""}: ${text}`)
    await Bun.sleep(2000 + Math.random() * 6000)
  }
}

const [command, ...rest] = process.argv.slice(2)
switch (command) {
  case "setup": await setup(); break
  case "serve": await serve(); break
  case "say": await say(rest[0] ?? "", rest.slice(1).join(" ")); break
  case "chatter": await chatter(); break
  default: console.log("usage: bun scripts/toasty/mock.ts setup | serve | say <name|group:name> <text> | chatter")
}
