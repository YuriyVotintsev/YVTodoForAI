import type { EngineInterface } from 'claude-code'

export type PromptName = 'review-manual' | 'review-live' | 'review-rules'

const MANIFEST_DIR = '/.claude-plugin'

export const slash = (path: string) => path.split('\\').join('/')

export function modRoot($: EngineInterface): string {
  const root = slash($.plugin.root).replace(/\/+$/, '')
  return root.endsWith(MANIFEST_DIR) ? root.slice(0, -MANIFEST_DIR.length) : root
}

export function scriptPath($: EngineInterface, name: string): string {
  return `${modRoot($)}/scripts/${name}`
}

export function promptPath($: EngineInterface, name: PromptName): string {
  return `${modRoot($)}/prompts/${name}.md`
}

export async function presetsPath($: EngineInterface): Promise<string | undefined> {
  const home = (await $.env.get('USERPROFILE')) ?? (await $.env.get('HOME'))
  return home ? slash(`${home}/.claude/review-bridge/presets.json`) : undefined
}

export function commandPrompt($: EngineInterface, name: 'review-manual' | 'review-live', args: string): string {
  const intro = name === 'review-manual' ? 'Разбери review-комментарии пачкой' : 'Включи живой режим review-комментариев'
  const extra = args.trim() ? `\n\n${args.trim()}` : ''
  return `${intro}: выполни инструкцию из \`${promptPath($, name)}\` (папка мода — \`${modRoot($)}\`).${extra}`
}
