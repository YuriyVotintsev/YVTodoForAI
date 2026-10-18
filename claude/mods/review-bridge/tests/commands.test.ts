import { expect, test } from 'claude-code/testing'

import { MOD, session } from './world'

test('/review-manual hands Claude the batch instructions from the mod folder', async ($, on) => {
  const w = await session($, on)
  const result = await $.command.run({ command: 'review-manual', args: 'only Ufo' })
  await w.clock.settle()
  expect(result.text).toBeUndefined()
  expect(w.prompts).toEqual([
    `Разбери review-комментарии пачкой: выполни инструкцию из \`${MOD}/prompts/review-manual.md\` (папка мода — \`${MOD}\`).\n\nonly Ufo`,
  ])
})

test('/review-live hands over the live instructions and turns /review-auto off so nothing is handled twice', async ($, on) => {
  const w = await session($, on)
  const result = await $.command.run({ command: 'review-live', args: '' })
  await w.clock.settle()
  expect(result.text).toBe('/review-auto выключен: за комментариями теперь следит /review-live.')
  expect(w.prompts).toEqual([
    `Включи живой режим review-комментариев: выполни инструкцию из \`${MOD}/prompts/review-live.md\` (папка мода — \`${MOD}\`).`,
  ])
  expect((await $.command.run({ command: 'review-auto', args: '' })).text).toContain('выключена')
  expect((await $.command.run({ command: 'review-live', args: '' })).text).toBeUndefined()
})
