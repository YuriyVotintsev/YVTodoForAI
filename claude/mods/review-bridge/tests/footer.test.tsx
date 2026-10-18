import { expect, test } from 'claude-code/testing'
import type { On } from 'claude-code'
import type { Engine } from 'claude-code/testing'

import { COMMENTS, SESSION, session } from './world'

async function startSession($: Engine, on: On, comments: object[] | undefined, startGate?: Promise<void>) {
  on('ui.render', { component: 'SessionMode' }, ($, e) => {
    const { Text } = $.ui.resolve(e)
    return <Text>engine line</Text>
  })
  const files = comments ? { [COMMENTS]: JSON.stringify({ comments }) } : {}
  return session($, on, { files, isIgnored: true, startGate })
}

const open = (id: string, status = 'PENDING') => ({ id, comment: `fix ${id}`, status, sessionId: SESSION })

test('open comments show as a footer button that opens the review panel', async ($, on) => {
  const w = await startSession($, on, [open('a1'), open('b2', 'HAS_QUESTIONS'), open('c3', 'DONE')])
  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ plugin: 'review-bridge', surface, component: 'SessionMode', props: { modes: ['focus'] } })
    expect((await ui.find({ key: 'review-open-panel' }))?.text).toContain('Review: 2 open · 1 question')
    expect(await ui.find({ type: 'Text', text: /focus/ })).toBeDefined()
    await ui.press({ key: 'review-open-panel' })
    await ui.unmount()
  }
  expect(w.toasts).toEqual([])
  expect(w.opened).toEqual(['code-review', 'code-review'])
  expect(w.shown).toEqual(['preview', 'preview'])
})

test('with nothing open, or nothing of this session, the button stays to open the panel', async ($, on) => {
  const w = await startSession($, on, [open('a1', 'DONE'), { ...open('b2'), sessionId: 'session-other' }])
  const ui = await $.ui.mount({ plugin: 'review-bridge', surface: 'desktop', component: 'SessionMode', props: { modes: [] } })
  expect((await ui.find({ key: 'review-open-panel' }))?.text).toBe('Review')
  await ui.press({ key: 'review-open-panel' })
  expect(w.opened).toEqual(['code-review'])
  await ui.unmount()
})

test('a project with no review file leaves the footer to the engine', async ($, on) => {
  await startSession($, on, undefined)
  const ui = await $.ui.mount({ plugin: 'review-bridge', surface: 'desktop', component: 'SessionMode', props: { modes: [] } })
  expect(await ui.find({ key: 'review-open-panel' })).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: /engine line/ })).toBeDefined()
  await ui.unmount()
})

test('the button says it is opening the panel until the panel is up', async ($, on) => {
  let release = () => {}
  const gate = new Promise<void>(resolve => { release = resolve })
  const w = await startSession($, on, [open('a1')], gate)
  const ui = await $.ui.mount({ plugin: 'review-bridge', surface: 'desktop', component: 'SessionMode', props: { modes: [] } })
  const pressed = ui.press({ key: 'review-open-panel' })
  await w.clock.settle()
  expect((await ui.find({ key: 'review-open-panel' }))?.text).toContain('открываю панель')
  release()
  await pressed
  expect((await ui.find({ key: 'review-open-panel' }))?.text).toContain('Review: 1 open')
  await ui.unmount()
})
