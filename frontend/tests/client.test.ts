import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiGet, apiPost, request, unwrap } from '../src/api/client'

function jsonRes(body: unknown, status = 200, statusText = 'OK'): Response {
  return new Response(JSON.stringify(body), { status, statusText, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('unwrap', () => {
  it('returns data on code 0', () => {
    expect(unwrap({ code: '0', msg: '', data: [1, 2] }, 200)).toEqual([1, 2])
  })

  it('throws ApiError with msg on non-zero code', () => {
    try {
      unwrap({ code: '51000', msg: 'Parameter period error' }, 200)
      throw new Error('should have thrown')
    } catch (e: any) {
      expect(e).toBeInstanceOf(ApiError)
      expect(e.message).toBe('Parameter period error')
      expect(e.code).toBe('51000')
      expect(e.status).toBe(200)
    }
  })

  it('falls back to default message when msg missing', () => {
    expect(() => unwrap({ code: '50011' }, 500)).toThrow('接口返回错误码 50011')
  })

  it('passes through payloads without code', () => {
    expect(unwrap({ status: 'UP' }, 200)).toEqual({ status: 'UP' })
  })
})

describe('request', () => {
  it('appends query and parses envelope', async () => {
    const fetchMock = vi.fn(async () => jsonRes({ code: '0', msg: '', data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    const out = await apiGet('/api/v1/klines', { instId: 'BTC-USDT-SWAP', bar: '', limit: 10 })
    expect(out).toEqual({ ok: true })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/v1/klines?instId=BTC-USDT-SWAP&limit=10')
    expect(init.method).toBe('GET')
    expect((init.headers as Record<string, string>).Accept).toBe('application/json')
  })

  it('sends json body for POST', async () => {
    const fetchMock = vi.fn(async () => jsonRes({ code: '0', msg: '', data: 7 }))
    vi.stubGlobal('fetch', fetchMock)
    const out = await apiPost('/api/v1/sync/basic', { days: 7 })
    expect(out).toBe(7)
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ days: 7 }))
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  it('maps network failures to ApiError NETWORK', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new Error('boom')
    }))
    await expect(apiGet('/api/v1/sync/status')).rejects.toMatchObject({
      name: 'ApiError',
      code: 'NETWORK',
      status: 0
    })
  })

  it('surfaces http error msg from envelope', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes({ code: 'HTTP_500', msg: 'boom happened' }, 500, 'Internal Server Error')))
    await expect(request('GET', '/api/v1/klines')).rejects.toMatchObject({
      message: 'boom happened',
      status: 500,
      code: 'HTTP_500'
    })
  })

  it('reports non-json bodies as BAD_JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 200 })))
    await expect(apiGet('/api/v1/sync/status')).rejects.toMatchObject({ code: 'BAD_JSON' })
  })
})
