export interface Persistence {
  get(key: string): Promise<string | null>
  set(key: string, value: string, ttlMillis?: number): Promise<void>
  remove(key: string): Promise<void>
}

export interface NetworkRequest {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  headers?: Record<string, string>
  body?: string
  timeoutMs?: number
}

export interface NetworkResponse {
  status: number
  headers: Record<string, string>
  body: string
}

export interface Networking {
  request(req: NetworkRequest): Promise<NetworkResponse>
}

export interface Clock {
  nowMillis(): number
}

export class SystemClock implements Clock {
  nowMillis(): number {
    return Date.now()
  }
}
