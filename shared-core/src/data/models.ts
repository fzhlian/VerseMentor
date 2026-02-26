export interface PoemLine {
  text: string
  meaning?: string
}

export interface Poem {
  id: string
  title: string
  dynasty: string
  author: string
  lines: PoemLine[]
}

export interface AuthorMeta {
  name: string
  dynasty?: string
  aliases?: string[]
  notes?: string
}
