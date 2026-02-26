import { HttpClient } from '../ports/net'

export interface VariantCandidate {
  text: string
  confidence?: number
  meaning?: string
}

export interface ProviderLineResult {
  lineIndex: number
  variants: VariantCandidate[]
  meaning?: string
}

export interface ProviderResult {
  provider: string
  lines: ProviderLineResult[]
}

export interface IVariantProvider {
  name: string
  fetch(poem: { title: string; author?: string; dynasty?: string }): Promise<ProviderResult>
}

export class EmptyVariantProvider implements IVariantProvider {
  name = 'empty'

  async fetch(_poem: { title: string; author?: string; dynasty?: string }): Promise<ProviderResult> {
    return { provider: this.name, lines: [] }
  }
}

export interface HttpVariantProviderConfig {
  name: string
  endpoint: string
  timeoutMs?: number
}

function buildQuery(params: Record<string, string>): string {
  const parts: string[] = []
  for (const key of Object.keys(params)) {
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
  }
  return parts.join('&')
}

export class HttpVariantProvider implements IVariantProvider {
  name: string
  private endpoint: string
  private client: HttpClient
  private timeoutMs?: number

  constructor(client: HttpClient, config: HttpVariantProviderConfig) {
    this.client = client
    this.name = config.name
    this.endpoint = config.endpoint
    this.timeoutMs = config.timeoutMs
  }

  async fetch(poem: { title: string; author?: string; dynasty?: string }): Promise<ProviderResult> {
    const query = buildQuery({
      title: poem.title,
      author: poem.author ?? '',
      dynasty: poem.dynasty ?? ''
    })
    const url = `${this.endpoint}?${query}`
    const result = await this.client.getJson<ProviderResult>(url)
    return {
      provider: result.provider || this.name,
      lines: result.lines ?? []
    }
  }
}
