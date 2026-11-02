/* K 线结果表列定义：与 legacy app.js KLINE_COLS 逐列等价（render 返回已转义 HTML） */
import { esc, fmtNum, fmtPct, fmtTime, isNil } from '../../lib/format'
import type { TableCol } from '../../components/DataTable.vue'
import type { KlineRow } from './types'

export const KLINE_COLS: TableCol[] = [
  { title: '时间', render: (r: KlineRow) => '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>' },
  { title: '开盘价', num: true, render: (r: KlineRow) => fmtNum(r.open) },
  { title: '最高价', num: true, render: (r: KlineRow) => fmtNum(r.high) },
  { title: '最低价', num: true, render: (r: KlineRow) => fmtNum(r.low) },
  { title: '收盘价', num: true, render: (r: KlineRow) => fmtNum(r.close) },
  {
    title: '涨跌幅',
    num: true,
    render: (r: KlineRow) => {
      const o = Number(r.open)
      const c = Number(r.close)
      if (!isFinite(o) || !isFinite(c) || o === 0) return '-'
      const pct = ((c - o) / o) * 100
      const cls = pct > 0 ? 'danger-text' : ''
      return '<span class="' + cls + '">' + esc(fmtPct(pct)) + '</span>'
    }
  },
  { title: '成交量 vol', num: true, render: (r: KlineRow) => fmtNum(r.vol) },
  { title: '成交额（计价币）', num: true, render: (r: KlineRow) => fmtNum(r.volCcyQuote) },
  {
    title: '已确认',
    num: true,
    render: (r: KlineRow) => esc(isNil(r.confirm) ? '-' : r.confirm ? '是' : '否')
  }
]
