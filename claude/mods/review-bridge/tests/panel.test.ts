import { expect, test } from 'claude-code/testing'

import { IDLE_EXIT_S, ownPanelVerdict, PREVIEW_START, SHOW_PANE } from '../hooks/panel'
import { EXCLUDE, LAUNCH, PRESETS, ROOT, SCRIPT, SESSION, session } from './world'

const IDLE = ['--exit-when-idle', String(IDLE_EXIT_S), '--session', SESSION, '--presets', PRESETS]
const MINE = { project: ROOT, session: SESSION }
const config = (text: string | undefined) =>
  (JSON.parse(text ?? '{}').configurations ?? []).find((c: { name?: string }) => c.name === 'code-review')

test('/review-panel starts the server itself and attaches the preview to it, quietly', async ($, on) => {
  const w = await session($, on, { files: { [EXCLUDE]: '# git ls-files --others --exclude-from=.git/info/exclude' } })
  const result = await $.command.run({ command: 'review-panel', args: '' })
  expect(result.text).toBeUndefined()
  expect(config(w.files.get(LAUNCH))).toEqual({ name: 'code-review', url: 'http://localhost:7790', port: 7790 })
  expect(w.files.get(EXCLUDE)).toEndWith('\n**/.claude/launch.json\n')
  expect(w.spawned).toEqual([['python', '-B', SCRIPT, '--port', '7790', ...IDLE]])
  expect(w.opened).toEqual(['code-review'])
  expect(w.shown).toEqual(['preview'])
})

test('the server outlives the tab: a second open reuses it, other arguments restart it, the session end stops it', async ($, on) => {
  const w = await session($, on, { isIgnored: true })
  await $.command.run({ command: 'review-panel', args: '' })
  await $.command.run({ command: 'review-panel', args: '' })
  expect(w.spawned).toHaveLength(1)
  await $.command.run({ command: 'review-panel', args: '--base develop' })
  expect(w.spawned).toEqual([['python', '-B', SCRIPT, '--port', '7790', ...IDLE], ['python', '-B', SCRIPT, '--port', '7790', ...IDLE, '--base', 'develop']])
  expect(w.served[7790]).toEqual(MINE)
  await $.session.end({ reason: 'exit' })
  await w.clock.settle()
  expect(w.served[7790]).toBeUndefined()
})

test('a server that stopped itself after the panel was closed is started again on the next open', async ($, on) => {
  const w = await session($, on, { isIgnored: true })
  await $.command.run({ command: 'review-panel', args: '' })
  w.exitIdle(7790)
  await w.clock.settle()
  expect(w.served[7790]).toBeUndefined()
  expect((await $.command.run({ command: 'review-panel', args: '' })).text).toBeUndefined()
  expect(w.spawned).toHaveLength(2)
  expect(w.served[7790]).toEqual(MINE)
  expect(w.opened).toEqual(['code-review', 'code-review'])
})

test('another project holding the port moves the panel to the next one', async ($, on) => {
  const w = await session($, on, { ports: { 7790: 'D:/other' }, isIgnored: true })
  expect((await $.command.run({ command: 'review-panel', args: '--base develop' })).text).toBeUndefined()
  expect(config(w.files.get(LAUNCH))?.url).toBe('http://localhost:7791')
  expect(w.spawned).toEqual([['python', '-B', SCRIPT, '--port', '7791', ...IDLE, '--base', 'develop']])
  expect(w.files.has(EXCLUDE)).toBe(false)
})

test("this session's own running panel is opened as it is, another session's is passed over", async ($, on) => {
  const other = { project: ROOT.split('/').join('\\'), session: 'session-other' }
  const w = await session($, on, { ports: { 7790: 'D:/other', 7791: other, 7792: { ...MINE, project: other.project } }, isIgnored: true })
  await $.command.run({ command: 'review-panel', args: '' })
  expect(config(w.files.get(LAUNCH))?.port).toBe(7792)
  expect(w.spawned).toEqual([])
  expect(w.opened).toEqual(['code-review'])
})

test('a panel of the same project but another session gets a server of its own', async ($, on) => {
  const w = await session($, on, { ports: { 7790: { project: ROOT, session: 'session-other' } }, isIgnored: true })
  await $.command.run({ command: 'review-panel', args: '' })
  expect(w.spawned).toEqual([['python', '-B', SCRIPT, '--port', '7791', ...IDLE]])
  expect(w.served[7791]).toEqual(MINE)
})

test('a set-up project is left as it is and other launch configurations are kept', async ($, on) => {
  const launch = JSON.stringify({
    version: '0.0.1',
    configurations: [
      { name: 'web', runtimeExecutable: 'npm', runtimeArgs: ['run', 'dev'], port: 3000 },
      { name: 'code-review', url: 'http://localhost:7790', port: 7790 },
    ],
  }, null, 2) + '\n'
  const w = await session($, on, { files: { [LAUNCH]: launch }, isTracked: true })
  await $.command.run({ command: 'review-panel', args: '' })
  expect(w.files.get(LAUNCH)).toBe(launch)
  expect(w.git).toEqual([])
  expect(w.opened).toEqual(['code-review'])
})

test('a refused or missing piece is reported, never guessed around', async ($, on) => {
  const w = await session($, on, { deny: 'not allowed here', isIgnored: true })
  expect((await $.command.run({ command: 'review-panel', args: '' })).text).toBe('Панель не открыта: not allowed here')
  w.files.delete(SCRIPT)
  expect((await $.command.run({ command: 'review-panel', args: '' })).text).toBe(`Нет ${SCRIPT}: мод review-bridge установлен не целиком.`)
})

test('a server that dies on start is reported with its error and nothing is opened', async ($, on) => {
  const w = await session($, on, { isIgnored: true, serverError: 'OSError: [WinError 10048] address already in use' })
  const result = await $.command.run({ command: 'review-panel', args: '' })
  expect(result.text).toBe('Панель не открыта: сервер панели завершился (код 1): Traceback (most recent call last): OSError: [WinError 10048] address already in use')
  expect(w.opened).toEqual([])
})

test('the mod lets only its own panel calls through a permission ask', () => {
  const ask = { decision: 'ask' as const }
  const verdict = (tool: string, origin: string, input: object, v: { decision: 'ask' | 'deny' } = ask) =>
    ownPanelVerdict(tool, origin, 'review-bridge', input, v)
  expect(verdict(PREVIEW_START, 'review-bridge', { name: 'code-review' }).decision).toBe('allow')
  expect(verdict(SHOW_PANE, 'review-bridge', { pane: 'preview' }).decision).toBe('allow')
  expect(verdict(PREVIEW_START, 'engine', { name: 'code-review' })).toBe(ask)
  expect(verdict(PREVIEW_START, 'other-plugin', { name: 'code-review' })).toBe(ask)
  expect(verdict(PREVIEW_START, 'review-bridge', { name: 'web' })).toBe(ask)
  expect(verdict(PREVIEW_START, 'review-bridge', { name: 'code-review', url: 'https://x' })).toBe(ask)
  expect(verdict(SHOW_PANE, 'review-bridge', { pane: 'file', path: 'secret.txt' })).toBe(ask)
  expect(verdict('Bash', 'review-bridge', { command: 'rm -rf /' })).toBe(ask)
  const denied = { decision: 'deny' as const }
  expect(verdict(PREVIEW_START, 'review-bridge', { name: 'code-review' }, denied)).toBe(denied)
})

test('a check another caller raises is passed through untouched', async ($, on) => {
  on('tool.check', ($, e) => ({ decision: 'ask' }))
  const verdict = await $.tool.check({ tool: 'mcp__Claude_Browser__preview_start', input: { name: 'code-review' } })
  expect(verdict.decision).toBe('ask')
})

test('an open browser is shown before the server is started; an empty one after it', async ($, on) => {
  const warm = await session($, on, { isIgnored: true })
  await $.command.run({ command: 'review-panel', args: '' })
  expect(warm.calls).toEqual(['show', 'spawn', 'start'])
})

test('with no page in the browser yet it is shown once the panel is up', async ($, on) => {
  const cold = await session($, on, { isIgnored: true, hasNoPage: true })
  const result = await $.command.run({ command: 'review-panel', args: '' })
  expect(cold.calls).toEqual(['show (no page)', 'spawn', 'start', 'show'])
  expect(result.text).toBeUndefined()
})
