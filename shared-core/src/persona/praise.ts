export class PraisePicker {
  private history: string[]
  private maxHistory: number

  constructor(private templates: string[], maxHistory = 5) {
    this.history = []
    this.maxHistory = maxHistory
  }

  next(): string {
    const recent = new Set(this.history)
    const candidates = this.templates.filter((t) => !recent.has(t))
    const pool = candidates.length > 0 ? candidates : this.templates
    const choice = pool[Math.floor(Math.random() * pool.length)]
    this.history.push(choice)
    if (this.history.length > this.maxHistory) {
      this.history.shift()
    }
    return choice
  }

  snapshot(): string[] {
    return [...this.history]
  }

  restore(history: string[]): void {
    this.history = history.slice(-this.maxHistory)
  }
}

export const PRAISE_TEMPLATES: string[] = [
  '很好！',
  '太棒了！',
  '背得很准。',
  '节奏很稳。',
  '发音清楚。',
  '记得很牢。',
  '状态不错。',
  '继续保持。',
  '很有感觉。',
  '气息到位。',
  '非常流畅。',
  '这段很熟。',
  '记忆力真好。',
  '掌握得很好。',
  '进步明显。',
  '表现优秀。',
  '很稳！',
  '很顺！',
  '相当精准。',
  '很有韵味。',
  '抑扬顿挫不错。',
  '背得真好。',
  '继续！',
  '不错不错。',
  '很棒的表现。',
  '气口掌握得好。',
  '朗朗上口。',
  '你背得真顺。',
  '节奏感很强。',
  '这首你很熟。',
  '稳住，太好了。',
  '很有把握。',
  '越来越好了。',
  '这句很标准。',
  '表现很出色。'
]
