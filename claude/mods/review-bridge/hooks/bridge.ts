import { isMine, isOpen, parseComments, promptText, scan, statusText } from './review'
import type { Comment, State } from './review'

export type Timer = { cancel: () => void }
export type BridgeIO = {
  stat: (path: string) => Promise<number | undefined>
  read: (path: string) => Promise<string>
  write: (path: string, text: string) => Promise<void>
  now: () => Promise<number>
  after: (ms: number, fn: () => void) => Timer
  submit: (text: string) => Promise<unknown>
  status: (text: string | undefined) => void
  session: () => Promise<string>
}

export const FILE = '.ai-review-comments.json'
export const QUIET_MS = 2500
export const MAX_WAIT_MS = 10000
export const BEAT_MS = 5000
export const LIVE_FRESH_MS = 60000
const MAX_EVENTS = 20

type LiveFile = { heartbeat?: number; comments?: State }

export function createBridge(io: BridgeIO, root: string, isEnabled: boolean) {
  const file = `${root}/${FILE}`
  const live = `${file}.live`
  let enabled = isEnabled
  let state: State = {}
  let comments: Comment[] = []
  let mtime: number | undefined | null = null
  let busy = false
  let pending: string[] = []
  let firstAt = 0
  let timer: Timer | undefined
  let lastBeat = 0
  let session = ''

  const mine = () => comments.filter(c => isMine(c, session))
  const showStatus = () => io.status(statusText(mine(), enabled, mtime != null))

  async function load(): Promise<Comment[] | undefined> {
    try {
      return parseComments(await io.read(file))
    } catch {
      return undefined
    }
  }

  async function readLive(): Promise<LiveFile | undefined> {
    try {
      return JSON.parse(await io.read(live))
    } catch {
      return undefined
    }
  }

  const writeLive = (heartbeat: number) =>
    io.write(live, JSON.stringify({ heartbeat, pid: 0, source: 'review-bridge', comments: state }))

  async function flush() {
    timer?.cancel()
    timer = undefined
    const blocks = pending.splice(0)
    firstAt = 0
    if (!blocks.length || !enabled) return
    const shown = blocks.length > MAX_EVENTS
      ? [...blocks.slice(0, MAX_EVENTS), `… и ещё ${blocks.length - MAX_EVENTS}, полный список — инструмент \`open\``]
      : blocks
    await io.submit(promptText(shown))
  }

  async function queue(found: string[]) {
    if (!found.length || !enabled) return
    const now = await io.now()
    pending.push(...found)
    if (!firstAt) firstAt = now
    timer?.cancel()
    const waited = now - firstAt
    if (waited >= MAX_WAIT_MS) {
      await flush()
      return
    }
    timer = io.after(Math.min(QUIET_MS, MAX_WAIT_MS - waited), () => {
      void flush()
    })
  }

  async function start() {
    session = await io.session()
    const current = await io.stat(file)
    if (current === undefined) {
      mtime = undefined
      showStatus()
      return
    }
    const list = await load()
    if (!list) return
    mtime = current
    comments = list
    const saved = await readLive()
    const now = await io.now()
    const isFresh = saved?.comments !== undefined && typeof saved.heartbeat === 'number'
      && now - saved.heartbeat < LIVE_FRESH_MS
    const result = await scan(isFresh ? saved.comments ?? {} : {}, list, session)
    state = result.state
    if (isFresh) await queue(result.events)
    showStatus()
  }

  async function tick() {
    if (busy) return
    busy = true
    try {
      const current = await io.stat(file)
      const id = await io.session()
      if (current !== mtime || id !== session) {
        session = id
        const list = current === undefined ? [] : await load()
        if (list) {
          mtime = current
          const result = await scan(state, list, session)
          state = result.state
          comments = list
          await queue(result.events)
          showStatus()
        }
      }
      const now = await io.now()
      if (now - lastBeat >= BEAT_MS) {
        lastBeat = now
        showStatus()
        if (current !== undefined && enabled) await writeLive(now)
      }
    } finally {
      busy = false
    }
  }

  async function setEnabled(value: boolean) {
    enabled = value
    if (value) {
      lastBeat = 0
    } else {
      pending = []
      firstAt = 0
      timer?.cancel()
      timer = undefined
      if (mtime != null) await writeLive(0)
    }
    showStatus()
  }

  async function stop() {
    timer?.cancel()
    timer = undefined
    if (enabled && mtime != null) await writeLive(0)
  }

  return {
    start,
    tick,
    setEnabled,
    stop,
    isEnabled: () => enabled,
    openCount: () => mine().filter(isOpen).length,
  }
}
