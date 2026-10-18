import { expect, test } from 'claude-code/testing'

import { COMMENTS, HELPER, session } from './world'

const tool = (name: string) => `mcp__review-bridge__${name}`

test('the review tools are registered up front, with no ToolSearch step', async ($, on) => {
  const w = await session($, on)
  expect(w.tools).toEqual(['open', 'show', 'status', 'reply'].map(name => ({ name, isDeferred: false })))
})

test('reply writes to the thread through ai_review.py, the text on stdin and the status beside it', async ($, on) => {
  const w = await session($, on)
  const answer = await $.tool.call({ tool: tool('reply'), id: '66bee705', text: 'Готово: переименовал метод.\n', status: 'DONE' })
  expect(answer.isError).toBeUndefined()
  expect(answer.result).toBe('66bee705: say ok')
  expect(w.helper).toEqual([{ argv: [HELPER, '--file', COMMENTS, 'say', '66bee705', 'DONE'], stdin: 'Готово: переименовал метод.' }])
})

test('open, show and status map onto the same helper commands', async ($, on) => {
  const w = await session($, on)
  await $.tool.call({ tool: tool('open') })
  await $.tool.call({ tool: tool('show'), id: 'a1' })
  await $.tool.call({ tool: tool('status'), id: 'a1', status: 'IN_PROGRESS' })
  expect(w.helper.map(r => r.argv.slice(3))).toEqual([['open'], ['show', 'a1'], ['status', 'a1', 'IN_PROGRESS']])
})

test('a bad call is refused before anything runs, and a helper failure reaches the model as an error', async ($, on) => {
  const w = await session($, on, { helperError: 'No comment zz' })
  const empty = await $.tool.call({ tool: tool('reply'), id: 'a1', text: '  ' })
  expect(empty.isError).toBe(true)
  expect(empty.text).toBe('text is required')
  const closed = await $.tool.call({ tool: tool('status'), id: 'a1', status: 'CLOSED' })
  expect(closed.text).toBe('status must be one of IN_PROGRESS, HAS_QUESTIONS, DONE, CANCELED')
  expect(w.helper).toEqual([])
  const missing = await $.tool.call({ tool: tool('show'), id: 'zz' })
  expect(missing.isError).toBe(true)
  expect(missing.text).toBe('No comment zz')
})

test('the tools pass a permission ask, a deny still wins', async ($, on) => {
  let decision: 'ask' | 'deny' = 'ask'
  on('tool.check', () => ({ decision }))
  expect((await $.tool.check({ tool: tool('reply'), input: { id: 'a1', text: 'x' } })).decision).toBe('allow')
  decision = 'deny'
  expect((await $.tool.check({ tool: tool('reply'), input: { id: 'a1', text: 'x' } })).decision).toBe('deny')
})
