export type ScriptPreference = 'SYSTEM' | 'SIMPLIFIED' | 'TRADITIONAL'

export interface Poem {
  id: string
  title: string
  author: string
  dynasty: string
  linesSimplified: string[]
  linesTraditional: string[]
}

export interface PoemLineVariant {
  lineIndex: number
  variants: string[]
}

export interface PoemVariants {
  poemId: string
  lines: PoemLineVariant[]
  sourceTags: string[]
}

export interface TtsVoice {
  id: string
  displayName: string
  style: string
}
