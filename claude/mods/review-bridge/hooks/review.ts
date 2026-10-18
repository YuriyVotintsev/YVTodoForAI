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
  sessionId?: string | null
  thread?: ThreadMessage[]
  questions?: Question[]
}
export type Fingerprint = {
  status: string
  text: string
  users: number
  answers: (string | null)[]
  range: [string | null, number | null, number | null]
  session?: string | null
}
export type State = Record<string, Fingerprint>

const status = (c: Comment) => c.status ?? 'PENDING'
export const isOpen = (c: Comment) => OPEN.includes(status(c))
export const isMine = (c: Comment, session: string) => c.sessionId === session

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
    session: c.sessionId ?? null,
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
const CLIP = 2000

export function full(text: string | undefined): string {
  const trimmed = (text ?? '').trim()
  return trimmed.length <= CLIP ? trimmed : trimmed.slice(0, CLIP - 1) + '…'
}

function block(c: Comment, note: string, body = ''): string {
  const head = [(c.id ?? '?').slice(0, 8), location(c), note].filter(Boolean).join(' · ')
  return body ? `${head}\n${body}` : head
}

export function events(prev: Fingerprint | undefined, cur: Fingerprint, c: Comment, session: string): string[] {
  if (!isMine(c, session)) return []
  const open = OPEN.includes(cur.status)
  if (!prev) return open ? [block(c, '', full(c.comment))] : []
  if (prev.session !== undefined && prev.session !== session) return open ? [block(c, 'перенесён в эту сессию', full(c.comment))] : []
  const out: string[] = []
  if (cur.users > prev.users) {
    const last = (c.thread ?? []).filter(m => m.author === 'user').at(-1)
    out.push(block(c, `ответ к «${short(c.comment, 60)}»`, full(last?.text)))
  }
  if (!same(cur.answers, prev.answers) && cur.answers.some(a => a != null && a !== '')) {
    const answered = (c.questions ?? []).filter(q => q.answer != null && q.answer !== '')
    out.push(block(c, 'ответы на вопросы', answered.map(q => `${short(q.question, 120)} → ${q.answer}`).join('\n')))
  }
  if (cur.text !== prev.text && open) out.push(block(c, 'текст изменён', full(c.comment)))
  if (!same(cur.range, prev.range) && open) out.push(block(c, 'переехал'))
  if (cur.status === 'PENDING' && ['DONE', 'CANCELED', 'CLOSED'].includes(prev.status) && cur.users === prev.users) {
    out.push(block(c, 'снова открыт', full(c.comment)))
  }
  return out
}

export async function scan(prev: State, comments: Comment[], session: string): Promise<{ events: string[]; state: State }> {
  const state: State = {}
  const found: string[] = []
  for (const c of comments) {
    if (!c.id) continue
    const cur = await fingerprint(c)
    found.push(...events(prev[c.id], cur, c, session))
    state[c.id] = cur
  }
  return { events: found, state }
}

export function promptText(blocks: string[], rules: string): string {
  return [
    ...blocks,
    'Это из панели ревью. Возьми (`status` IN_PROGRESS), сделай или ответь и напиши итог в переписку комментария ' +
      '(`reply`, статус DONE или HAS_QUESTIONS) инструментами review-bridge; порядок — «Реакция на комментарий» ' +
      `в \`${rules}\`.`,
  ].join('\n\n')
}

export function statusText(comments: Comment[], enabled: boolean, hasFile = true): string | undefined {
  const open = comments.filter(isOpen)
  const questions = open.filter(c => status(c) === 'HAS_QUESTIONS').length
  const working = open.filter(c => status(c) === 'IN_PROGRESS').length
  if (!open.length && !hasFile && enabled) return undefined
  const parts = [open.length ? `Review: ${open.length} open` : 'Review']
  if (working) parts.push(`${working} in progress`)
  if (questions) parts.push(`${questions} ${questions === 1 ? 'question' : 'questions'}`)
  if (!enabled) parts.push('auto off')
  return parts.join(' · ')
}
