import { describe, expect, it } from 'vitest'
import {
  badge,
  buildQuery,
  datetimeLocalToMs,
  esc,
  fmtDur,
  fmtInt,
  fmtPct,
  fmtRatio,
  fmtTime,
  isNil,
  pagerInfo,
  parseList,
  parseServerTime
} from '../src/lib/format'
import { NL, TAB } from '../src/lib/labels'

describe('format', () => {
  it('escapes html metacharacters', () => {
    const raw = '<a href="x">&' + String.fromCharCode(39) + '</a>'
    expect(esc(raw)).toBe('&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;')
    expect(esc(null)).toBe('')
    expect(isNil('')).toBe(true)
  })

  it('formats numbers and ratios', () => {
    expect(fmtInt(1234567)).toMatch(/1,234,567/)
    expect(fmtInt(null)).toBe('-')
    expect(fmtRatio(1.23456)).toBe('1.2346')
    expect(fmtPct(1.235)).toBe('+1.24%')
    expect(fmtPct(-1.235)).toBe('-1.24%')
    expect(fmtPct(null)).toBe('-')
  })

  it('formats timestamps', () => {
    const ms = new Date(2026, 10, 1, 9, 5, 3).getTime()
    expect(fmtTime(ms)).toBe('2026-11-01 09:05:03')
    expect(fmtTime(0)).toBe('-')
    expect(datetimeLocalToMs('2026-11-01T09:05')).toBe(new Date(2026, 10, 1, 9, 5).getTime())
    expect(datetimeLocalToMs('')).toBe(null)
  })

  it('truncates server time fractions to milliseconds', () => {
    expect(parseServerTime('2026-11-01T01:02:03.123456')).toBe(new Date(2026, 10, 1, 1, 2, 3, 123).getTime())
    expect(parseServerTime('2026-11-01T01:02:03.5')).toBe(new Date(2026, 10, 1, 1, 2, 3, 500).getTime())
    expect(parseServerTime('2026-11-01T01:02:03')).toBe(new Date(2026, 10, 1, 1, 2, 3).getTime())
    expect(Number.isNaN(parseServerTime(''))).toBe(true)
  })

  it('formats durations', () => {
    expect(fmtDur(500)).toBe('500 ms')
    expect(fmtDur(2500)).toBe('2.5 秒')
    expect(fmtDur(95000)).toBe('1 分 35 秒')
    expect(fmtDur(-1)).toBe('-')
  })

  it('parses separated lists and dedupes', () => {
    const sep = 'a, b;c|d' + NL + 'e' + TAB + 'f，g、h i'
    expect(parseList(sep, NL, TAB)).toEqual(['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'])
    expect(parseList('', NL, TAB)).toEqual([])
  })

  it('builds query strings skipping blanks', () => {
    expect(buildQuery({ a: 1, b: '', c: null, d: ' x ' })).toBe('?a=1&d=x')
    expect(buildQuery({})).toBe('')
    expect(buildQuery(null)).toBe('')
  })

  it('renders badges and pager info', () => {
    expect(badge('DONE')).toBe('<span class="badge" data-status="DONE">已完成</span>')
    expect(badge('SKIPPED')).toBe('<span class="badge" data-status="SKIPPED">已跳过</span>')
    expect(badge('WEIRD')).toBe('<span class="badge" data-status="WEIRD">WEIRD</span>')
    expect(pagerInfo(0, 20)).toBe('第 1–20 条')
    expect(pagerInfo(40, 10)).toBe('第 41–50 条')
    expect(pagerInfo(0, 0)).toBe('暂无记录')
  })
})
