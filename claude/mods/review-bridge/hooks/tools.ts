import type { EngineInterface } from 'claude-code'

import { FILE } from './bridge'
import { scriptPath } from './paths'

export const STATUSES = ['IN_PROGRESS', 'HAS_QUESTIONS', 'DONE', 'CANCELED']
export const REVIEW_TOOLS = [
  'mcp__review-bridge__open',
  'mcp__review-bridge__show',
  'mcp__review-bridge__status',
  'mcp__review-bridge__reply',
] as const
export type ReviewTool = (typeof REVIEW_TOOLS)[number]

const ID = { type: 'string', description: 'The comment id; its first 8 characters are enough' }
const STATUS = { type: 'string', enum: STATUSES }

export const TOOL_SPECS = [
  {
    name: 'open',
    description: 'Lists the branch review comments (.ai-review-comments.json) that wait for you: id, status, place, text and the latest reply of the person.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'show',
    description: 'One review comment in full: file and lines, selected code, diff snippet, revision, the thread and questions.',
    inputSchema: { type: 'object', properties: { id: ID }, required: ['id'] },
  },
  {
    name: 'status',
    description: 'Sets a review comment status: IN_PROGRESS as you take it, CANCELED when it is dropped. CLOSED is the person\'s, never set it.',
    inputSchema: { type: 'object', properties: { id: ID, status: STATUS }, required: ['id', 'status'] },
  },
  {
    name: 'reply',
    description: 'Writes to the person in the comment thread, which they read in the review panel, and can set the status at once: DONE when the work is done or the question answered, HAS_QUESTIONS when you ask.',
    inputSchema: {
      type: 'object',
      properties: { id: ID, text: { type: 'string', description: 'The message, plain text or Markdown' }, status: STATUS },
      required: ['id', 'text'],
    },
  },
]

export type ToolAnswer = { result: string } | { isError: true; result: string; text: string }
type Verdict = { decision: 'allow' | 'ask' | 'deny'; reason?: string }

export function ownToolVerdict<V extends Verdict>(verdict: V): V | Verdict {
  return verdict.decision === 'ask'
    ? { decision: 'allow', reason: 'review-bridge tools only read and answer .ai-review-comments.json' }
    : verdict
}

const failed = (text: string): ToolAnswer => ({ isError: true, result: text, text })
const field = (input: Record<string, unknown>, key: string) => (typeof input[key] === 'string' ? (input[key] as string).trim() : '')

export function reviewArgs(tool: ReviewTool, input: Record<string, unknown>): { argv: string[]; stdin?: string } | string {
  const id = field(input, 'id')
  const status = field(input, 'status')
  if (tool !== REVIEW_TOOLS[0] && !id) return 'id is required'
  if (status && !STATUSES.includes(status)) return `status must be one of ${STATUSES.join(', ')}`
  if (tool === REVIEW_TOOLS[0]) return { argv: ['open'] }
  if (tool === REVIEW_TOOLS[1]) return { argv: ['show', id] }
  if (tool === REVIEW_TOOLS[2]) return status ? { argv: ['status', id, status] } : 'status is required'
  const text = field(input, 'text')
  if (!text) return 'text is required'
  return { argv: ['say', id, ...(status ? [status] : [])], stdin: text }
}

export async function runReviewTool($: EngineInterface, tool: ReviewTool, input: Record<string, unknown>): Promise<ToolAnswer> {
  const args = reviewArgs(tool, input)
  if (typeof args === 'string') return failed(args)
  const root = await $.session.root()
  const script = scriptPath($, 'ai_review.py')
  if (!(await $.fs.exists(script))) return failed(`Нет ${script}: мод review-bridge установлен не целиком.`)
  const run = await $.process.run(['python', '-B', script, '--file', `${root}/${FILE}`, ...args.argv], {
    cwd: root,
    ...(args.stdin === undefined ? {} : { stdin: args.stdin }),
    timeoutMs: 15000,
  })
  const out = run.stdout.trim()
  if (run.exitCode !== 0) return failed(run.stderr.trim() || out || `ai_review.py завершился с кодом ${run.exitCode}`)
  return { result: out }
}
