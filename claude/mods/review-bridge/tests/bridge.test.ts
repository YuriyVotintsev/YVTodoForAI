import { expect, test } from 'claude-code/testing'

import { BEAT_MS, createBridge, FILE, QUIET_MS } from '../hooks/bridge'
import { sha1, statusText } from '../hooks/review'

const ROOT = 'D:/proj'
const PATH = `${ROOT}/${FILE}`
const LIVE = `${PATH}.live`

const ME = 'session-me'
const pending = (id: string, extra = {}) => ({ id, filePath: 'Assets/A.cs', startLine: 5, endLine: 7, comment: `fix ${id}`, status: 'PENDING', sessionId: ME, ...extra })

function setup(initial: object[] | undefined, isEnabled = true) {
  const files = new Map<string, { text: string; mtime: number }>()
  let clock = 1_000_000
  let session = ME
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
    session: async () => session,
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
  const clear = (id: string) => { session = id }
  return { bridge, submitted, statuses, advance, write, live, put, clear }
}

test('a new comment from the panel wakes Claude once, after a quiet pause', async () => {
  const s = setup([pending('a1', { status: 'DONE' })])
  await s.bridge.start()
  s.write([pending('a1', { status: 'DONE' }), pending('b2222222')])
  await s.bridge.tick()
  expect(s.submitted).toHaveLength(0)
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(1)
  expect(s.submitted[0]).toStartWith('b2222222 · Assets/A.cs:5-7\nfix b2222222\n\n')
  expect(s.submitted[0]).toContain('`reply`')
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
  expect(s.submitted[0]).toStartWith('a1 · Assets/A.cs:5-7 · ответ к «fix a1»\nB\n\n')
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
  expect(s.submitted[0]).toStartWith(['c1', 'c2', 'c3'].map(id => `${id} · Assets/A.cs:5-7\nfix ${id}`).join('\n\n'))
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

test('each session keeps its own heartbeat in the live file and takes only it away', async () => {
  const s = setup([pending('a1')])
  s.put(LIVE, { heartbeat: 999_000, sessions: { 'session-other': 999_000, 'session-gone': 900_000 }, comments: {} })
  await s.bridge.start()
  await s.bridge.tick()
  expect(s.live().sessions).toEqual({ 'session-other': 999_000, [ME]: 1_000_000 })
  expect(s.live().heartbeat).toBe(1_000_000)
  await s.bridge.stop()
  expect(s.live().sessions).toEqual({ 'session-other': 999_000 })
  expect(s.live().heartbeat).toBe(999_000)
})

test('a fresh heartbeat file lets a reloaded mod catch up on what it missed', async () => {
  const s = setup([pending('a1')])
  s.put(LIVE, { heartbeat: 1_000_000 - 5000, comments: {} })
  await s.bridge.start()
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(1)
  expect(s.submitted[0]).toStartWith('a1 · Assets/A.cs:5-7\nfix a1')
})

test('a folder comment and a general comment are named as such', async () => {
  const s = setup([])
  await s.bridge.start()
  s.write([{ id: 'f1', filePath: 'Assets/Ufo', kind: 'folder', comment: 'split it', status: 'PENDING', sessionId: ME }, { id: 'g1', comment: 'general', status: 'PENDING', sessionId: ME }])
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted[0]).toStartWith('f1 · Assets/Ufo/\nsplit it\n\ng1 · общий\ngeneral\n\n')
})

test('the prompt carries the whole comment, an edit and the answers to questions', async () => {
  const long = Array.from({ length: 40 }, (_, i) => `line ${i}`).join('\n')
  const s = setup([pending('q1', { status: 'HAS_QUESTIONS', questions: [{ question: 'A or B?', answer: null }] })])
  await s.bridge.start()
  s.write([pending('n1', { comment: long }), pending('q1', { status: 'HAS_QUESTIONS', comment: 'fix q1 now', questions: [{ question: 'A or B?', answer: 'B' }] })])
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted[0]).toContain(`n1 · Assets/A.cs:5-7\n${long}`)
  expect(s.submitted[0]).toContain('q1 · Assets/A.cs:5-7 · ответы на вопросы\nA or B? → B')
  expect(s.submitted[0]).toContain('q1 · Assets/A.cs:5-7 · текст изменён\nfix q1 now')
})

test("another session's comments and unbound ones are neither handed over nor counted", async () => {
  const s = setup([])
  await s.bridge.start()
  s.write([pending('o1', { sessionId: 'session-other' }), pending('u1', { sessionId: undefined }), pending('m1')])
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(1)
  expect(s.submitted[0]).toStartWith('m1 · Assets/A.cs:5-7\nfix m1\n\n')
  expect(s.submitted[0]).not.toContain('o1')
  expect(s.submitted[0]).not.toContain('u1')
  expect(s.statuses.at(-1)).toBe('Review: 1 open')
  expect(s.bridge.openCount()).toBe(1)
})

test('a comment taken over from another session reaches this one', async () => {
  const s = setup([pending('o1', { sessionId: 'session-other' })])
  await s.bridge.start()
  s.write([pending('o1')])
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(1)
  expect(s.submitted[0]).toStartWith('o1 · Assets/A.cs:5-7 · перенесён в эту сессию\nfix o1\n\n')
})

test('after /clear the bridge follows the new session id', async () => {
  const s = setup([pending('m1')])
  await s.bridge.start()
  s.clear('session-next')
  await s.bridge.tick()
  expect(s.bridge.openCount()).toBe(0)
  s.write([pending('m1'), pending('n1', { sessionId: 'session-next' })])
  await s.bridge.tick()
  await s.advance(QUIET_MS)
  expect(s.submitted).toHaveLength(1)
  expect(s.submitted[0]).toStartWith('n1 · Assets/A.cs:5-7\nfix n1')
})

test('the status line is sent again with every heartbeat, for a surface that attached late', async () => {
  const s = setup([pending('a1')])
  await s.bridge.start()
  await s.bridge.tick()
  const sent = s.statuses.length
  await s.advance(BEAT_MS)
  await s.bridge.tick()
  expect(s.statuses.length).toBe(sent + 1)
  expect(s.statuses.at(-1)).toBe('Review: 1 open')
})

test('the text fingerprint is the sha1 ai_review.py writes', async () => {
  expect(await sha1('test')).toBe('a94a8fe5ccb19ba61c4c0873d391e987982fbbd3')
})

test('the status line counts open work', () => {
  expect(statusText([], true, false)).toBeUndefined()
  expect(statusText([], true)).toBe('Review')
  expect(statusText([{ status: 'DONE' }], false)).toBe('Review · auto off')
  expect(statusText([], false, false)).toBe('Review · auto off')
  expect(statusText([{ status: 'PENDING' }, { status: 'IN_PROGRESS' }, { status: 'HAS_QUESTIONS' }], true))
    .toBe('Review: 3 open · 1 in progress · 1 question')
})
