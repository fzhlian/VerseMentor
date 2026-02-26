export interface HttpClient {
  getJson<T>(url: string, headers?: Record<string, string>): Promise<T>
}

export class MockHttpClient implements HttpClient {
  constructor(private fixtures: Record<string, unknown> = {}) {}

  async getJson<T>(url: string, _headers?: Record<string, string>): Promise<T> {
    if (url in this.fixtures) {
      return this.fixtures[url] as T
    }
    throw new Error(`No mock response for ${url}`)
  }
}
