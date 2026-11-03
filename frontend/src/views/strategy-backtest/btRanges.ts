/* 回测「参数范围（可选）」：与后端 ParamRanges 同一套语义。
   6 个维度留空 = 该维沿用内置网格；6 维全空 = 完全等价旧的 648 组网格。
   表单里一律存字符串（组件直接 v-model），提交前由 rangesToPayload 统一解析校验。 */

export const BT_RANGE_DIMS = [
  'leverage',
  'maxLayers',
  'layerStepPct',
  'layerScale',
  'takeProfitPct',
  'stopLossPct'
] as const

export type BtRangeDim = (typeof BT_RANGE_DIMS)[number]

export interface BtRangeForm {
  min: string
  max: string
  step: string
}

export interface BtRangesFormState {
  sides: string[]
  leverage: BtRangeForm
  maxLayers: BtRangeForm
  layerStepPct: BtRangeForm
  layerScale: BtRangeForm
  takeProfitPct: BtRangeForm
  stopLossPct: BtRangeForm
}

export interface BtRangePayload {
  min: number
  max: number
  step: number
}

export interface BtRangesPayload {
  leverage?: BtRangePayload
  maxLayers?: BtRangePayload
  layerStepPct?: BtRangePayload
  layerScale?: BtRangePayload
  takeProfitPct?: BtRangePayload
  stopLossPct?: BtRangePayload
  sides?: string[]
}

/** 维度元信息：label 用于提示文案，integral 区分杠杆/层数这类整数维，lower/upper 与后端校验范围一致。 */
export interface BtRangeDimMeta {
  key: BtRangeDim
  label: string
  integral: boolean
  lower?: number
  lowerExclusive?: boolean
  upper?: number
  /** 内置网格的默认取值个数（维度留空时按它估算组合数） */
  defaultCount: number
  /** 输入框 placeholder，展示内置网格的典型写法 */
  placeholder: string
}

export const BT_RANGE_META: BtRangeDimMeta[] = [
  { key: 'leverage', label: '杠杆', integral: true, lower: 1, upper: 125, defaultCount: 3, placeholder: '3 / 10 / 1' },
  { key: 'maxLayers', label: '最大层数', integral: true, lower: 1, upper: 50, defaultCount: 3, placeholder: '3 / 8 / 1' },
  { key: 'layerStepPct', label: '加仓间隔 %', integral: false, lower: 0, lowerExclusive: true, defaultCount: 3, placeholder: '1.5 / 4 / 0.5' },
  { key: 'layerScale', label: '加仓倍数', integral: false, lower: 1, defaultCount: 2, placeholder: '1 / 1.5 / 0.5' },
  { key: 'takeProfitPct', label: '止盈 %', integral: false, lower: 0, lowerExclusive: true, defaultCount: 3, placeholder: '1 / 3 / 0.5' },
  { key: 'stopLossPct', label: '止损 %', integral: false, lower: 1, upper: 100, defaultCount: 2, placeholder: '15 / 25 / 5' }
]

export const BT_MAX_COMBOS = 2000
export const BT_DEFAULT_SAMPLE_SIZE = 400
export const BT_RANGE_DEFAULT_COMBOS = 648

export const BT_SIDE_LONG = 'long'
export const BT_SIDE_SHORT = 'short'

export function emptyRangeForm(): BtRangeForm {
  return { min: '', max: '', step: '' }
}

/** 空范围：6 维全空 + 双向勾选（等价后端 ranges=null）。每次调用返回独立对象，避免表单间共享。 */
export function emptyRangesForm(): BtRangesFormState {
  return {
    sides: [BT_SIDE_LONG, BT_SIDE_SHORT],
    leverage: emptyRangeForm(),
    maxLayers: emptyRangeForm(),
    layerStepPct: emptyRangeForm(),
    layerScale: emptyRangeForm(),
    takeProfitPct: emptyRangeForm(),
    stopLossPct: emptyRangeForm()
  }
}

export interface BtPreset {
  key: string
  label: string
  note: string
  combos: number
  ranges: BtRangesFormState
}

function rangeText(min: number, max: number, step: number): BtRangeForm {
  return { min: String(min), max: String(max), step: String(step) }
}

function mkRanges(dims: Partial<Record<BtRangeDim, [number, number, number]>>): BtRangesFormState {
  const out = emptyRangesForm()
  for (const key of BT_RANGE_DIMS) {
    const dim = dims[key]
    if (dim) out[key] = rangeText(dim[0], dim[1], dim[2])
  }
  return out
}

/* 预设与后端 ParamPresets 一一对应（前端本地常量，不走接口，避免页面加载多一次请求）。
   组合数由维度精确计算，可能超过 BT_MAX_COMBOS：服务端会按上限抽样并标记 rangeTruncated。 */
export const BT_PRESETS: BtPreset[] = [
  {
    key: 'conservative',
    label: '保守',
    note: '小杠杆与少层数、窄止盈止损，优先看策略稳不稳，组合数最少。',
    combos: 0,
    ranges: mkRanges({
      leverage: [3, 5, 1],
      maxLayers: [3, 5, 1],
      layerStepPct: [1.5, 2.5, 0.5],
      layerScale: [1, 1, 0.5],
      takeProfitPct: [1, 2, 0.5],
      stopLossPct: [15, 25, 5]
    })
  },
  {
    key: 'balanced',
    label: '均衡',
    note: '杠杆与加仓间隔铺开，兼顾稳定性与收益弹性；组合数通常超过上限，会按上限抽样。',
    combos: 0,
    ranges: mkRanges({
      leverage: [3, 10, 1],
      maxLayers: [3, 5, 1],
      layerStepPct: [1.5, 5, 0.5],
      layerScale: [1, 1.5, 0.5],
      takeProfitPct: [1, 3, 0.5],
      stopLossPct: [15, 30, 5]
    })
  },
  {
    key: 'aggressive',
    label: '激进',
    note: '高杠杆、深层加仓与宽止盈，追求收益弹性，回撤风险最大。',
    combos: 0,
    ranges: mkRanges({
      leverage: [5, 10, 1],
      maxLayers: [5, 8, 1],
      layerStepPct: [2.5, 4, 0.5],
      layerScale: [1.5, 1.5, 0.5],
      takeProfitPct: [2, 4, 0.5],
      stopLossPct: [20, 30, 5]
    })
  }
]

// 预设组合数在模块加载后按维度算一次（保守 486 / 均衡 15360 / 激进 2880）
for (const preset of BT_PRESETS) preset.combos = estimateCombos(preset.ranges)

/** 契约名 PRESETS 的别名（模块内其它常量沿用 BT_ 前缀） */
export const PRESETS: BtPreset[] = BT_PRESETS

export function findBtPreset(key: string | null | undefined): BtPreset | null {
  const target = String(key || '').trim()
  if (!target) return null
  return BT_PRESETS.find((p) => p.key === target) || null
}

/** 预设 → 表单范围（深拷贝，避免直接改到常量）；未知 key 返回空范围。 */
export function rangesFromPreset(keyOrPreset: string | BtPreset | null | undefined): BtRangesFormState {
  const preset = typeof keyOrPreset === 'object' && keyOrPreset !== null ? keyOrPreset : findBtPreset(keyOrPreset as string)
  if (!preset) return emptyRangesForm()
  const out = emptyRangesForm()
  out.sides = preset.ranges.sides.slice()
  for (const meta of BT_RANGE_META) {
    out[meta.key] = { ...preset.ranges[meta.key] }
  }
  return out
}

/** 方向：恰好勾一个才下传；全勾 / 全不勾 = 双向（省略 sides）。 */
export function rangeSides(sides: string[] | null | undefined): string[] | null {
  const picked = (sides || []).filter((s) => s === BT_SIDE_LONG || s === BT_SIDE_SHORT)
  return picked.length === 1 ? picked : null
}

function dimCount(meta: BtRangeDimMeta, dim: BtRangeForm | null | undefined): number {
  const min = Number(String(dim?.min ?? '').trim())
  const max = Number(String(dim?.max ?? '').trim())
  const step = Number(String(dim?.step ?? '').trim())
  const blank =
    !String(dim?.min ?? '').trim() || !String(dim?.max ?? '').trim() || !String(dim?.step ?? '').trim()
  if (blank || !isFinite(min) || !isFinite(max) || !isFinite(step) || max < min || !(step > 0)) {
    return meta.defaultCount
  }
  if (meta.integral) {
    const span = Math.round(max) - Math.round(min)
    const stride = Math.max(1, Math.round(step))
    let count = Math.floor(span / stride) + 1
    if (span % stride !== 0) count += 1
    return Math.max(1, count)
  }
  const raw = (max - min) / step
  const eps = 1e-9 * Math.max(1, Math.abs(raw))
  return Math.max(1, Math.floor(raw + eps) + 1)
}

/** 组合数估算：留空/填一半的维度按内置网格取值个数计；方向全勾或全不勾按双向 ×2。 */
export function estimateCombos(ranges: BtRangesFormState | null | undefined): number {
  if (!ranges) return BT_RANGE_DEFAULT_COMBOS
  let total = 1
  for (const meta of BT_RANGE_META) total *= dimCount(meta, ranges[meta.key])
  total *= rangeSides(ranges.sides) ? 1 : 2
  return total
}

function parseDim(meta: BtRangeDimMeta, dim: BtRangeForm | null | undefined): BtRangePayload | null {
  const minRaw = String(dim?.min ?? '').trim()
  const maxRaw = String(dim?.max ?? '').trim()
  const stepRaw = String(dim?.step ?? '').trim()
  if (!minRaw && !maxRaw && !stepRaw) return null
  if (!minRaw || !maxRaw || !stepRaw) {
    throw new Error(meta.label + ' 范围需同时填写 min / max / step')
  }
  const min = Number(minRaw)
  const max = Number(maxRaw)
  const step = Number(stepRaw)
  if (!isFinite(min) || !isFinite(max) || !isFinite(step)) {
    throw new Error(meta.label + ' 范围需为数字')
  }
  if (min > max) throw new Error(meta.label + ' 的 min 不能大于 max')
  if (meta.integral) {
    if (!Number.isInteger(min) || !Number.isInteger(max) || !Number.isInteger(step) || step < 1) {
      throw new Error(meta.label + ' 的 min / max / step 需为整数且 step ≥ 1')
    }
  } else if (!(step > 0)) {
    throw new Error(meta.label + ' 的 step 需大于 0')
  }
  if (meta.lower !== undefined && (meta.lowerExclusive ? min <= meta.lower : min < meta.lower)) {
    throw new Error(meta.label + ' 的 min 需' + (meta.lowerExclusive ? '大于 ' : '不小于 ') + meta.lower)
  }
  if (meta.upper !== undefined && max > meta.upper) {
    throw new Error(meta.label + ' 的 max 不能超过 ' + meta.upper)
  }
  return { min, max, step }
}

/**
 * 表单 → 提交体 ranges。
 *
 * <p>6 维全空且双向 = null（不传 ranges，等价旧行为）；填了一半的维度直接抛错，
 * 由 btBuildPayload 转成中文 toast。</p>
 */
export function rangesToPayload(ranges: BtRangesFormState | null | undefined): BtRangesPayload | null {
  if (!ranges) return null
  const payload: BtRangesPayload = {}
  let touched = false
  for (const meta of BT_RANGE_META) {
    const parsed = parseDim(meta, ranges[meta.key])
    if (!parsed) continue
    payload[meta.key] = parsed
    touched = true
  }
  const sides = rangeSides(ranges.sides)
  if (sides) {
    payload.sides = sides
    touched = true
  }
  return touched ? payload : null
}

function numText(value: number): string {
  return String(Math.round(value * 10000) / 10000)
}

/** 展示用：'3-10 步 1'；空 range 返回 '—'。 */
export function formatRange(range: BtRangePayload | null | undefined): string {
  if (!range) return '—'
  return numText(range.min) + '-' + numText(range.max) + ' 步 ' + numText(range.step)
}
