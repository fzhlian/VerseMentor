import { normalizeZh, stripPunct } from './zh_normalize'

export function slugZh(s: string): string {
  const normalized = normalizeZh(s)
  const noPunct = stripPunct(normalized)
  const spaced = noPunct.replace(/\s+/g, ' ').trim()
  return spaced.replace(/\s+/g, '-').replace(/[^a-z0-9\u4e00-\u9fff-]/g, '')
}

export function hash32(s: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < s.length; i++) {
    hash ^= s.charCodeAt(i)
    hash = (hash * 0x01000193) >>> 0
  }
  return hash.toString(16).padStart(8, '0')
}

export function makeDynastyId(norm: string): string {
  return `dyn_${hash32(norm)}`
}

export function makeAuthorId(norm: string): string {
  return `auth_${hash32(norm)}`
}
