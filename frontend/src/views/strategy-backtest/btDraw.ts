/* 资金曲线 canvas 绘制：逐行等价 legacy drawBtEquity（app.js 4112-4256），
   几何计算放在 btLogic.btEquityGeometry 里便于单测，这里只负责画。 */
import { fmtMoney, fmtShortTime } from '../../lib/format'
import { CHART_FONT, COLOR_AXIS, COLOR_GRID } from '../../lib/labels'
import { btEquityGeometry, type BtEquityPoint } from './btLogic'

export interface BtDrawOutcome {
  /* 画布尺寸是否可用（容器被隐藏时 clientWidth=0 也按最小尺寸画） */
  width: number
  height: number
  drawn: boolean
}

export function drawBtEquity(canvas: HTMLCanvasElement | null, points: BtEquityPoint[]): BtDrawOutcome {
  if (!canvas) return { width: 0, height: 0, drawn: false }
  const wrap = canvas.parentElement
  const w = Math.max(320, wrap ? wrap.clientWidth || 600 : 600)
  const h = Math.max(220, wrap ? wrap.clientHeight || 300 : 300)
  const dpr = window.devicePixelRatio || 1
  canvas.width = Math.round(w * dpr)
  canvas.height = Math.round(h * dpr)
  canvas.style.width = w + 'px'
  canvas.style.height = h + 'px'
  const ctx = canvas.getContext('2d')
  if (!ctx) return { width: w, height: h, drawn: false }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, w, h)

  const geo = btEquityGeometry(points, w, h)
  const emptyText = function (text: string) {
    ctx.fillStyle = COLOR_AXIS
    ctx.font = '12px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(text, w / 2, h / 2)
  }
  if (!geo.ok) {
    emptyText(geo.reason === 'no-equity' ? '资金曲线缺少 equity 字段' : '暂无资金曲线')
    return { width: w, height: h, drawn: false }
  }

  ctx.font = CHART_FONT
  ctx.lineWidth = 1
  for (let g = 0; g < geo.ticks.length; g++) {
    const t = geo.ticks[g]
    ctx.strokeStyle = COLOR_GRID
    ctx.beginPath()
    ctx.moveTo(geo.padL, t.y)
    ctx.lineTo(geo.padL + geo.plotW, t.y)
    ctx.stroke()
    ctx.fillStyle = COLOR_AXIS
    ctx.textAlign = 'right'
    ctx.textBaseline = 'middle'
    ctx.fillText(fmtMoney(t.value), geo.padL - 8, t.y)
  }

  const baseY = geo.base
  if (isFinite(baseY)) {
    const y = geo.padT + geo.plotH - ((baseY - geo.min) / (geo.max - geo.min)) * geo.plotH
    ctx.save()
    ctx.setLineDash([4, 4])
    ctx.strokeStyle = COLOR_AXIS
    ctx.beginPath()
    ctx.moveTo(geo.padL, y)
    ctx.lineTo(geo.padL + geo.plotW, y)
    ctx.stroke()
    ctx.restore()
  }

  const cs = geo.coords
  if (cs.length) {
    ctx.beginPath()
    ctx.moveTo(cs[0][0], cs[0][1])
    for (let i = 1; i < cs.length; i++) ctx.lineTo(cs[i][0], cs[i][1])
    ctx.strokeStyle = geo.color
    ctx.lineWidth = 1.6
    ctx.stroke()
    ctx.lineTo(cs[cs.length - 1][0], geo.padT + geo.plotH)
    ctx.lineTo(cs[0][0], geo.padT + geo.plotH)
    ctx.closePath()
    ctx.fillStyle = geo.fillColor
    ctx.fill()
  }

  ctx.fillStyle = COLOR_AXIS
  ctx.textBaseline = 'top'
  ctx.textAlign = 'left'
  ctx.fillText(fmtShortTime(geo.firstTs), geo.padL, geo.padT + geo.plotH + 8)
  ctx.textAlign = 'right'
  ctx.fillText(fmtShortTime(geo.lastTs), geo.padL + geo.plotW, geo.padT + geo.plotH + 8)
  return { width: w, height: h, drawn: true }
}
