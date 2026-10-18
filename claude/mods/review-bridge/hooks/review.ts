export const OPEN = ['PENDING', 'IN_PROGRESS', 'HAS_QUESTIONS']

export type ThreadMessage = { author?: string; text?: string; at?: number }
export type Question = { question?: string; options?: string[]; answer?: string | null }
export type Comment = {
  id?: string
  filePath?: string | null
  startLine?: number | null
  endLine?: number | null
  comment?: string
  status?: string
  kind?: string
  thread?: ThreadMessage[]
  questions?: Question[]
}
export type Fingerprint = {
  status: string
  text: string
  users: number
  answers: (string | null)[]
  range: [string | null, number | null, number | null]
}
export type State = Record<string, Fingerprint>

const status = (c: Comment) => c.status ?? 'PENDING'
export const isOpen = (c: Comment) => OPEN.includes(status(c))

export function parseComments(text: string): Comment[] | undefined {
  const body = text.replace(/^﻿/, '')
  if (!body.trim()) return []
  try {
    const data = JSON.parse(body)
    const list = Array.isArray(data) ? data : data?.comments
    return Array.isArray(list) ? list : []
  } catch {
    return undefined
  }
}

export async function sha1(text: string): Promise<string> {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-1', new TextEncoder().encode(text)))
  return [...bytes].map(b => b.toString(16).padStart(2, '0')).join('')
}

export async function fingerprint(c: Comment): Promise<Fingerprint> {
  return {
    status: status(c),
    text: await sha1(c.comment ?? ''),
    users: (c.thread ?? []).filter(m => m.author === 'user').length,
    answers: (c.questions ?? []).map(q => q.answer ?? null),
    range: [c.filePath ?? null, c.startLine ?? null, c.endLine ?? null],
  }
}

export function location(c: Comment): string {
  if (!c.filePath) return 'общий'
  if (c.startLine == null) return c.kind === 'folder' ? `${c.filePath}/` : c.filePath
  const end = c.endLine
  return `${c.filePath}:${!end || end === c.startLine ? c.startLine : `${c.startLine}-${end}`}`
}

export function short(text: string | undefined, n = 90): string {
  const flat = (text ?? '').split(/\s+/).filter(Boolean).join(' ')
  return flat.length <= n ? flat : flat.slice(0, n - 1) + '…'
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

export function events(prev: Fingerprint | undefined, cur: Fingerprint, c: Comment): string[] {
  const head = `${(c.id ?? '?').slice(0, 8)} ${location(c)}`
  const open = OPEN.includes(cur.status)
  if (!prev) return open ? [`NEW ${head} — ${short(c.comment)}`] : []
  const out: string[] = []
  if (cur.users > prev.users) {
    const last = (c.thread ?? []).filter(m => m.author === 'user').at(-1)
    out.push(`REPLY ${head} — ${short(last?.text)}`)
  }
  if (!same(cur.answers, prev.answers) && cur.answers.some(a => a != null && a !== '')) out.push(`ANSWER ${head}`)
  if (cur.text !== prev.text && open) out.push(`EDIT ${head} — ${short(c.comment)}`)
  if (!same(cur.range, prev.range) && open) out.push(`MOVED ${head}`)
  if (cur.status === 'PENDING' && ['DONE', 'CANCELED', 'CLOSED'].includes(prev.status) && cur.users === prev.users) {
    out.push(`REOPEN ${head} — ${short(c.comment)}`)
  }
  return out
}

export async function scan(prev: State, comments: Comment[]): Promise<{ events: string[]; state: State }> {
  const state: State = {}
  const found: string[] = []
  for (const c of comments) {
    if (!c.id) continue
    const cur = await fingerprint(c)
    found.push(...events(prev[c.id], cur, c))
    state[c.id] = cur
  }
  return { events: found, state }
}

export function promptText(lines: string[]): string {
  return [
    'Новые события в review-комментариях (`.ai-review-comments.json`), их заметил мод review-bridge:',
    ...lines.map(line => `- ${line}`),
    '',
    'Обработай их по разделу «Реакция на события» из `~/.claude/commands/review_live.md`: `show <id>`, ' +
      '`status <id> IN_PROGRESS`, затем ответ или работа и `say <id> DONE|HAS_QUESTIONS` через ' +
      '`python ~/.claude/scripts/ai_review.py`. Сторож через Monitor не запускай: за файлом следит мод.',
  ].join('\n')
}

export function statusText(comments: Comment[], enabled: boolean): string | undefined {
  const open = comments.filter(isOpen)
  const questions = open.filter(c => status(c) === 'HAS_QUESTIONS').length
  const working = open.filter(c => status(c) === 'IN_PROGRESS').length
  if (!open.length) return enabled ? undefined : 'Review: auto off'
  const parts = [`Review: ${open.length} open`]
  if (working) parts.push(`${working} in progress`)
  if (questions) parts.push(`${questions} ${questions === 1 ? 'question' : 'questions'}`)
  if (!enabled) parts.push('auto off')
  return parts.join(' · ')
}
