import { expect, test } from 'claude-code/testing'

import { createBridge, FILE, QUIET_MS } from '../hooks/bridge'
import { sha1, statusText } from '../hooks/review'

const ROOT = 'D:/proj'
const PATH = `${ROOT}/${FILE}`
const LIVE = `${PATH}.live`

const pending = (id: string, extra = {}) => ({ id, filePath: 'Assets/A.cs', startLine: 5, endLine: 7, comment: `fix ${id}`, status: 'PENDING', ...extra })

function setup(initial: object[] | undefined, isEnabled = true) {
  const files = new Map<string, { text: string; mtime: number }>()
  let clock = 1_000_000
  let version = 0
  const timers: { at: number; fn: () => void; isLive: boolean }[] = []
  const submitted: string[] = []
  const statuses: (string | undefined)[] = []
  const put = (path: string, data: unknown) => files.set(path, { text: JSON.stringify(data), mtime: ++version })
  if (initial) put(PATH, { comments: initial })
  const bridge = createBridge({
    stat: async path => files.get(path)?.mtime,
    read: async path => {
      const found = files.get(path)
      if (!found) throw new Error('ENOENT')
      return found.text
    },
    write: async (path, text) => {
      files.set(path, { text, mtime: ++version })
    },
    now: async () => clock,
    after: (ms, fn) => {
      const timer = { at: clock + ms, fn, isLive: true }
      timers.push(timer)
      return { cancel: () => { timer.isLive = false } }
    },
    submit: async text => {
      submitted.push(text)
    },
    status: text => {
      statuses.push(text)
    },
  }, ROOT, isEnabled)
  const settle = async () => {
    for (let i = 0; i < 20; i++) await Promise.resolve()
  }
  const advance = async (ms: number) => {
    clock += ms
    for (const timer of timers) {
      if (timer.isLive && timer.at <= clock) {
        timer.isLive = false
        timer.fn()
      }
    }
    await settle()
  }
  const write = (comments: object[]) => put(PATH, { comments })
  const live = () => JSON.parse(files.get(LIVE)?.text ?? '{}')
  return { bridge, submitted, statuses, advance, write, live, put }
}

test('a new comment from the panel wakes Claude once, after a quiet pause', async () => {
  const s = setup([pending('a1', { status: 'DONE' })])
  await s.bridge.start()
  s.write([pending('a1', { status: 'DONE' }), pending('b2222222')])
  await s.bridge.tick()
  expect(s.submitted).toHaveLength(0)
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(1)
  expect(s.submitted[0]).toContain('NEW b2222222 Assets/A.cs:5-7 — fix b2222222')
  expect(s.submitted[0]).toContain('review_live.md')
  expect(s.statuses.at(-1)).toBe('Review: 1 open')
})

test('comments that were already there when the session started are not replayed', async () => {
  const s = setup([pending('a1'), pending('b2')])
  await s.bridge.start()
  await s.bridge.tick()
  await s.advance(QUIET_MS * 4)
  expect(s.submitted).toHaveLength(0)
  expect(s.statuses.at(-1)).toBe('Review: 2 open')
})

test("Claude's own progress triggers nothing, the person's reply does", async () => {
  const s = setup([pending('a1')])
  await s.bridge.start()
  s.write([pending('a1', { status: 'IN_PROGRESS', thread: [{ author: 'claude', text: 'on it' }] })])
  await s.bridge.tick()
  s.write([pending('a1', { status: 'HAS_QUESTIONS', thread: [{ author: 'claude', text: 'A or B?' }] })])
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(0)
  s.write([pending('a1', { status: 'HAS_QUESTIONS', thread: [{ author: 'claude', text: 'A or B?' }, { author: 'user', text: 'B' }] })])
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(1)
  expect(s.submitted[0]).toContain('REPLY a1 Assets/A.cs:5-7 — B')
})

test('a burst of comments goes to Claude as one prompt', async () => {
  const s = setup([])
  await s.bridge.start()
  const list: object[] = []
  for (const id of ['c1', 'c2', 'c3']) {
    list.push(pending(id))
    s.write([...list])
    await s.bridge.tick()
    await s.advance(1000)
  }
  expect(s.submitted).toHaveLength(0)
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(1)
  for (const id of ['c1', 'c2', 'c3']) expect(s.submitted[0]).toContain(`NEW ${id}`)
})

test('off stops prompts and drops the live heartbeat; on resumes without replaying', async () => {
  const s = setup([pending('a1')])
  await s.bridge.start()
  await s.bridge.tick()
  expect(s.live().heartbeat).toBeGreaterThan(0)
  await s.bridge.setEnabled(false)
  expect(s.live().heartbeat).toBe(0)
  s.write([pending('a1'), pending('b2')])
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(0)
  expect(s.statuses.at(-1)).toBe('Review: 2 open · auto off')
  await s.bridge.setEnabled(true)
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(0)
  expect(s.live().heartbeat).toBeGreaterThan(0)
})

test('a fresh heartbeat file lets a reloaded mod catch up on what it missed', async () => {
  const s = setup([pending('a1')])
  s.put(LIVE, { heartbeat: 1_000_000 - 5000, comments: {} })
  await s.bridge.start()
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(1)
  expect(s.submitted[0]).toContain('NEW a1')
})

test('a folder comment and a general comment are named as such', async () => {
  const s = setup([])
  await s.bridge.start()
  s.write([{ id: 'f1', filePath: 'Assets/Ufo', kind: 'folder', comment: 'split it', status: 'PENDING' }, { id: 'g1', comment: 'general', status: 'PENDING' }])
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted[0]).toContain('NEW f1 Assets/Ufo/ — split it')
  expect(s.submitted[0]).toContain('NEW g1 общий — general')
})

test('the text fingerprint is the sha1 ai_review.py writes', async () => {
  expect(await sha1('test')).toBe('a94a8fe5ccb19ba61c4c0873d391e987982fbbd3')
})

test('the status line counts open work', () => {
  expect(statusText([], true)).toBeUndefined()
  expect(statusText([{ status: 'DONE' }], false)).toBe('Review: auto off')
  expect(statusText([{ status: 'PENDING' }, { status: 'IN_PROGRESS' }, { status: 'HAS_QUESTIONS' }], true))
    .toBe('Review: 3 open · 1 in progress · 1 question')
})
