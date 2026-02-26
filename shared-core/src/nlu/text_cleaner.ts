import { dedupeSpeechFiller, stripPunct } from '../utils/zh_normalize'

export function cleanSpeechText(raw: string): string {
  const deDuped = dedupeSpeechFiller(raw)
  return stripPunct(deDuped).trim()
}
