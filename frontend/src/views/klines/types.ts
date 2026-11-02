/* klines 页私有类型（字段名与 legacy app.js 的 JSON 字段一致） */

export interface KlineRow {
  tsMs?: number | string | null
  open?: number | string | null
  high?: number | string | null
  low?: number | string | null
  close?: number | string | null
  vol?: number | string | null
  volCcyQuote?: number | string | null
  confirm?: boolean | null
  [key: string]: unknown
}

/* 按列聚合后的蜡烛（aggregateCandles 产物） */
export interface AggregatedCandle {
  tsMs: number
  open: number | null
  close: number | null
  high: number
  low: number
  vol: number
  merged: number
}

export type LineKey = 'close' | 'ma5' | 'ma20' | 'ma60'
export type RatioKey = 'ratioPos' | 'ratioAcc' | 'ratioLsr'

export interface ChartOpts {
  close: boolean
  ma5: boolean
  ma20: boolean
  ma60: boolean
  ratioPos: boolean
  ratioAcc: boolean
  ratioLsr: boolean
}

export interface RatioSpec {
  key: RatioKey
  ratioType: string
  label: string
}

/* [tsMs, ratio] */
export type RatioPoint = [number, number]

export interface RatioCache {
  instId: string
  bar: string
  period: string
  startMs: number | null
  endMs: number | null
  series: Record<RatioKey, RatioPoint[]>
}

export interface ChartInfo {
  truncated: boolean
}

export interface LastQuery {
  instId: string
  bar: string
  startMs: number | null
  endMs: number | null
}
