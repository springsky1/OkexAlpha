/* 与 legacy app.js 第 2 区逐行等价的请求封装（统一 ApiResponse 解析与错误提示） */
import { buildQuery } from '../lib/format'

export class ApiError extends Error {
  readonly status: number
  readonly code: string | number | undefined

  constructor(message: string, status: number, code?: string | number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

/* 统一解析 {code:"0",msg:"",data:...}；code 非 "0" 抛错并带出 msg */
export function unwrap<T = any>(payload: unknown, status: number): T {
  if (payload && typeof payload === 'object' && !Array.isArray(payload) && (payload as any).code !== undefined) {
    const p = payload as { code: unknown; msg?: string }
    if (String(p.code) !== '0') {
      throw new ApiError(p.msg || ('接口返回错误码 ' + p.code), status, p.code as string)
    }
    return (payload as any).data as T
  }
  return payload as T
}

export async function request<T = any>(
  method: string,
  path: string,
  params?: Record<string, unknown> | null,
  body?: unknown
): Promise<T> {
  const url = path + buildQuery(params)
  const init: RequestInit = { method: method, headers: { Accept: 'application/json' }, cache: 'no-store' }
  if (body !== undefined && body !== null) {
    (init.headers as Record<string, string>)['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  let res: Response
  try {
    res = await fetch(url, init)
  } catch (err) {
    throw new ApiError('无法连接后端服务（' + url + '），请确认服务已启动', 0, 'NETWORK')
  }
  const text = await res.text()
  let payload: any = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch (err) {
      payload = null
    }
  }
  if (!res.ok) {
    const msg = payload && payload.msg ? payload.msg : ('HTTP ' + res.status + ' ' + (res.statusText || '请求失败'))
    throw new ApiError(msg, res.status, (payload && payload.code) || ('HTTP_' + res.status))
  }
  if (!payload) {
    throw new ApiError('响应不是合法的 JSON（HTTP ' + res.status + '）', res.status, 'BAD_JSON')
  }
  return unwrap<T>(payload, res.status)
}

export function apiGet<T = any>(path: string, params?: Record<string, unknown> | null): Promise<T> {
  return request<T>('GET', path, params, null)
}

export function apiPost<T = any>(path: string, body?: unknown, params?: Record<string, unknown> | null): Promise<T> {
  return request<T>('POST', path, params, body)
}

export function apiRawGet<T = any>(path: string): Promise<T> {
  return request<T>('GET', path, null, null)
}
