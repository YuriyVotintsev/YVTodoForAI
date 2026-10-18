import type { EngineInterface } from 'claude-code'

import { presetsPath, scriptPath, slash } from './paths'
import type { ReviewServer } from './server'

export const SERVER_NAME = 'code-review'
export const NOT_STARTED = 'review-bridge ещё не запущен.'
const PORTS = [7790, 7791, 7792, 7793, 7794, 7795, 7796, 7797, 7798, 7799]
export const IDLE_EXIT_S = 60

export const PREVIEW_START = 'mcp__Claude_Browser__preview_start'
export const SHOW_PANE = 'mcp__ccd_view__show_pane'

export type PanelResult = { isOpened: boolean; text?: string }
export type Verdict = { decision: 'allow' | 'ask' | 'deny'; reason?: string }

function isPanelCall(tool: string, input: unknown) {
  const args = (input ?? {}) as Record<string, unknown>
  const keys = Object.keys(args).sort().join(',')
  if (tool === PREVIEW_START) return keys === 'name' && args.name === SERVER_NAME
  if (tool === SHOW_PANE) return keys === 'pane' && args.pane === 'preview'
  return false
}

export function ownPanelVerdict<V extends Verdict>(tool: string, origin: string | undefined, self: string, input: unknown, verdict: V): V | Verdict {
  return origin === self && verdict.decision === 'ask' && isPanelCall(tool, input)
    ? { decision: 'allow', reason: `${self} opens its own review panel` }
    : verdict
}
type LaunchConfig = { name?: string; url?: string; runtimeExecutable?: string; runtimeArgs?: string[]; port?: number }
type LaunchFile = { version?: string; configurations?: LaunchConfig[] }

const samePath = (a: string, b: string) => slash(a).replace(/\/+$/, '').toLowerCase() === slash(b).replace(/\/+$/, '').toLowerCase()

async function portOwner($: EngineInterface, port: number): Promise<{ project?: unknown; session?: unknown } | null | undefined> {
  try {
    const response = await $.http.fetch(`http://127.0.0.1:${port}/api/info`)
    return response.ok ? JSON.parse(response.text) ?? null : null
  } catch {
    return undefined
  }
}

async function pickPort($: EngineInterface, root: string, session: string, preferred: number | undefined): Promise<{ port: number; isServed: boolean } | undefined> {
  for (const port of [...new Set([...(preferred ? [preferred] : []), ...PORTS])]) {
    const owner = await portOwner($, port)
    if (owner === undefined) return { port, isServed: false }
    if (typeof owner?.project === 'string' && samePath(owner.project, root) && owner.session === session) return { port, isServed: true }
  }
  return undefined
}

async function excludeFromGit($: EngineInterface, root: string) {
  const git = (...args: string[]) => $.process.run(['git', ...args], { cwd: root })
  const tracked = await git('ls-files', '--error-unmatch', '.claude/launch.json').catch(() => undefined)
  if (!tracked || tracked.exitCode === 0) return
  if ((await git('check-ignore', '-q', '.claude/launch.json')).exitCode === 0) return
  const found = await git('rev-parse', '--git-path', 'info/exclude')
  if (found.exitCode !== 0) return
  const relative = found.stdout.trim()
  const path = /^([a-zA-Z]:)?[\\/]/.test(relative) ? relative : `${root}/${relative}`
  const text = await $.fs.read(path).then(String, () => '')
  await $.fs.write(path, `${text}${text && !text.endsWith('\n') ? '\n' : ''}**/.claude/launch.json\n`)
}

export async function openReviewPanel($: EngineInterface, args: string, server: ReviewServer | undefined): Promise<PanelResult> {
  if (!server) return { isOpened: false, text: NOT_STARTED }
  const isShownEarly = (await callTool($, SHOW_PANE, { pane: 'preview' })) === undefined
  const root = await $.session.root()
  const script = scriptPath($, 'ai_review_web.py')
  if (!(await $.fs.exists(script))) return { isOpened: false, text: `Нет ${script}: мод review-bridge установлен не целиком.` }

  const launchPath = `${root}/.claude/launch.json`
  const launchText = await $.fs.read(launchPath).then(String, () => undefined)
  let launch: LaunchFile = { version: '0.0.1', configurations: [] }
  if (launchText !== undefined) {
    try {
      launch = JSON.parse(launchText)
    } catch {
      return { isOpened: false, text: '.claude/launch.json не читается как JSON: исправь его, и /review-panel допишет конфигурацию.' }
    }
  }
  const configurations = Array.isArray(launch.configurations) ? launch.configurations : []
  const current = configurations.find(c => c?.name === SERVER_NAME)

  const session = await $.session.id()
  const own = server.port()
  const picked = own === undefined ? await pickPort($, root, session, current?.port) : { port: own, isServed: false }
  if (!picked) return { isOpened: false, text: `Порты ${PORTS[0]}–${PORTS.at(-1)} заняты панелями других проектов и сессий.` }
  const { port } = picked

  const wanted: LaunchConfig = { name: SERVER_NAME, url: `http://localhost:${port}`, port }
  if (JSON.stringify(current) !== JSON.stringify(wanted)) {
    launch.configurations = [...configurations.filter(c => c?.name !== SERVER_NAME), wanted]
    if (!launch.version) launch.version = '0.0.1'
    await $.fs.write(launchPath, JSON.stringify(launch, null, 2) + '\n')
    await excludeFromGit($, root).catch(() => undefined)
  }

  if (!picked.isServed) {
    const extra = args.trim() ? args.trim().split(/\s+/) : []
    const presets = await presetsPath($)
    const argv = ['python', '-B', script, '--port', String(port), '--exit-when-idle', String(IDLE_EXIT_S), '--session', session,
      ...(presets ? ['--presets', presets] : []), ...extra]
    const down = await server.ensure(port, argv, root)
    if (down) return { isOpened: false, text: `Панель не открыта: ${down}` }
  }

  const failed = await callTool($, PREVIEW_START, { name: SERVER_NAME })
  if (failed) return { isOpened: false, text: `Панель не открыта: ${failed}` }
  if (isShownEarly) return { isOpened: true }
  const hidden = await callTool($, SHOW_PANE, { pane: 'preview' })
  return hidden ? { isOpened: true, text: `Панель запущена, но браузер Claude Desktop не показан: ${hidden}` } : { isOpened: true }
}

async function callTool($: EngineInterface, tool: string, input: Record<string, unknown>): Promise<string | undefined> {
  try {
    const result = await $.tool.call({ tool, ...input })
    if (result.deny !== undefined) return result.deny
    if (result.isError) return result.text ?? JSON.stringify(result.result)
    return undefined
  } catch (error) {
    return `${tool} недоступен (${error instanceof Error ? error.message : String(error)})`
  }
}
