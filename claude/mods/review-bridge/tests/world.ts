import { mock } from 'claude-code/testing'
import type { On } from 'claude-code'
import type { Engine } from 'claude-code/testing'

export const ROOT = 'D:/proj'
export const HOME = 'C:/Users/u'
export const SCRIPT = `${HOME}/.claude/scripts/ai_review_web.py`
export const HELPER = `${HOME}/.claude/scripts/ai_review.py`
export const LAUNCH = `${ROOT}/.claude/launch.json`
export const EXCLUDE = 'D:/.git/info/exclude'
export const COMMENTS = `${ROOT}/.ai-review-comments.json`
export const SESSION = 'session-1'

type Owner = { project: string; session?: string }

const norm = (path: string) => path.split('\\').join('/')
const run = (exitCode: number, stdout = '') => ({ value: { exitCode, stdout, stderr: '' } })

export type WorldOptions = {
  files?: Record<string, string>
  ports?: Record<number, string | Owner>
  isTracked?: boolean
  isIgnored?: boolean
  deny?: string
  hasNoPage?: boolean
  startGate?: Promise<void>
  serverError?: string
  helperError?: string
}

export function world(on: On, options: WorldOptions = {}) {
  const files = new Map(Object.entries({ [SCRIPT]: '# server', [HELPER]: '# helper', ...options.files }))
  const served: Record<number, Owner> = Object.fromEntries(
    Object.entries(options.ports ?? {}).map(([port, owner]) => [port, typeof owner === 'string' ? { project: owner } : owner]))
  const spawned: string[][] = []
  const git: string[] = []
  const helper: { argv: string[]; stdin?: string }[] = []
  const tools: { name: string; isDeferred?: unknown }[] = []
  const exits: Record<number, () => void> = {}
  const opened: string[] = []
  const shown: string[] = []
  const calls: string[] = []
  const toasts: string[] = []
  mock.env(on, { USERPROFILE: HOME })
  on('session.root', () => ({ value: ROOT }))
  on('session.id', () => ({ value: SESSION }))
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  on('session.end', () => ({ sessionId: 'test' }))
  on('command.register', ($, e) => ({ value: { command: e.name } }))
  on('tool.register', ($, e) => {
    tools.push({ name: e.name, isDeferred: e.isDeferred })
    return { value: { tool: `mcp__review-bridge__${e.name}` } }
  })
  on('ui.status', () => ({ value: undefined }))
  on('ui.toast', ($, e) => {
    toasts.push(e.text)
    return { value: undefined }
  })
  on('fs.exists', ($, e) => ({ value: files.has(norm(e.path)) }))
  on('fs.stat', ($, e) => {
    if (!files.has(norm(e.path))) throw new Error('ENOENT')
    return { value: { kind: 'file', size: 1, mtimeMs: 1, isLink: false } }
  })
  on('fs.read', ($, e) => {
    const text = files.get(norm(e.path))
    if (text === undefined) throw new Error('ENOENT')
    return { value: text }
  })
  on('fs.write', ($, e) => {
    files.set(norm(e.path), e.text)
    return { value: undefined }
  })
  on('http.fetch', ($, e) => {
    const owner = served[Number(/:(\d+)\//.exec(e.url)?.[1])]
    if (owner === undefined) throw new Error('ECONNREFUSED')
    return { value: { status: 200, ok: true, headers: {}, text: JSON.stringify(owner) } }
  })
  on('process.run', ($, e) => {
    if (e.argv[0] === 'python') {
      helper.push({ argv: e.argv.slice(1), stdin: e.init?.stdin })
      if (options.helperError) return { value: { exitCode: 1, stdout: '', stderr: options.helperError } }
      return run(0, `${e.argv[5]}: ${e.argv[4]} ok\n`)
    }
    const command = e.argv[1]
    git.push(command)
    if (command === 'ls-files') return run(options.isTracked ? 0 : 1)
    if (command === 'check-ignore') return run(options.isIgnored ? 0 : 1)
    if (command === 'rev-parse') return run(0, '../.git/info/exclude\n')
    return run(1)
  })
  on('process.spawn', async function* ($, e, next) {
    calls.push('spawn')
    spawned.push([...e.argv])
    if (options.serverError) {
      yield { stream: 'stderr' as const, text: `Traceback (most recent call last):\n${options.serverError}\n` }
      return { value: { code: 1, signal: null } }
    }
    const port = Number(e.argv[e.argv.indexOf('--port') + 1])
    served[port] = { project: ROOT, session: e.argv[e.argv.indexOf('--session') + 1] }
    try {
      yield { stream: 'stdout' as const, text: `AI review web: http://127.0.0.1:${port}  project=${ROOT}  base=main\n` }
      await new Promise<void>(resolve => {
        exits[port] = resolve
        next.signal.addEventListener('abort', () => resolve())
      })
    } finally {
      delete served[port]
    }
    return { value: { code: 0, signal: null } }
  })
  let hasPage = !options.hasNoPage
  on('tool.call', { tool: 'mcp__Claude_Browser__preview_start' }, async ($, e) => {
    calls.push('start')
    if (options.deny) return { deny: options.deny }
    await options.startGate
    opened.push(String((e as { name?: unknown }).name))
    hasPage = true
    return { result: 'Attached the preview to the configured url; no process was started.' }
  })
  on('tool.call', { tool: 'mcp__ccd_view__show_pane' }, ($, e) => {
    calls.push(hasPage ? 'show' : 'show (no page)')
    if (!hasPage) return { result: 'The Browser pane has no page open.', isError: true, text: 'The Browser pane has no page open.' }
    shown.push(String((e as { pane?: unknown }).pane))
    return { result: 'Showing the preview pane.' }
  })
  const exitIdle = (port: number) => exits[port]?.()
  return { files, served, spawned, git, helper, tools, exitIdle, opened, shown, toasts, calls }
}

export async function session($: Engine, on: On, options: WorldOptions = {}) {
  const clock = mock.clock(on, { now: 1_000_000 })
  mock.store(on)
  const w = world(on, options)
  await $.session.start({ cwd: ROOT, surface: 'desktop', isInteractive: true })
  await clock.settle()
  return { ...w, clock }
}
