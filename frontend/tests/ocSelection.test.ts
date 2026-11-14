import { describe, it, expect } from 'vitest'

import { ocRowKey, ocSelectionPairs, ocSelectionSummary } from '../src/views/order-center/oc'

/*
 * 勾选汇总口径（订单中心 4 个只读 tab 通用）：
 *   已实现盈亏 = 行的 pnl 之和；
 *   手续费     = 行的 fee 之和（OKX 返回负数，即成本）；
 *   资金费     = 行的 fundingFee 之和（只有历史仓位有），没有该字段按 0；
 *   净收益     = 已实现盈亏 + 手续费 + 资金费（手续费为负，所以是减掉成本）；
 *   胜率       = pnl > 0 的笔数 / pnl ≠ 0 的笔数（开仓单 pnl=0 不参与），没有可比笔数时为 null。
 */

const orders = [
  {
    ordId: '1001',
    instId: 'BTC-USDT-SWAP',
    pnl: '3.5',
    fee: '-0.35',
    state: 'filled',
    cTime: '1700000000000',
    uTime: '1700000000000'
  },
  {
    ordId: '1002',
    instId: 'BTC-USDT-SWAP',
    pnl: '-1.2',
    fee: '-0.12',
    state: 'filled',
    cTime: '1700086400000',
    uTime: '1700086400000'
  },
  {
    ordId: '1003',
    instId: 'ETH-USDT-SWAP',
    pnl: null,
    fee: '-0.1',
    state: 'filled',
    cTime: '1700000000000',
    uTime: '1700000000000'
  }
]

const positions = [
  {
    instId: 'ETH-USDT-SWAP',
    posSide: 'short',
    pnl: '-12.5',
    fee: '-0.2',
    fundingFee: '0.05',
    cTime: '1700000000000',
    uTime: '1700000100000'
  }
]

const fills = [
  {
    tradeId: 'T1',
    ordId: '1001',
    instId: 'BTC-USDT-SWAP',
    fillSz: '1',
    fillPx: '70000',
    fee: '-0.14',
    pnl: '2.4',
    fillTime: '1700000200000'
  }
]

describe('ocSelectionSummary', () => {
  it('汇总委托订单：盈亏、手续费、净收益、胜率与按合约/按日分组', () => {
    const summary = ocSelectionSummary('history', orders)

    expect(summary.count).toBe(3)
    expect(summary.realizedPnl).toBeCloseTo(2.3, 8)
    expect(summary.fee).toBeCloseTo(-0.57, 8)
    expect(summary.fundingFee).toBeCloseTo(0, 8)
    expect(summary.netPnl).toBeCloseTo(1.73, 8)
    expect(summary.wins).toBe(1)
    expect(summary.losses).toBe(1)
    expect(summary.winRate).toBeCloseTo(0.5, 8)
    expect(summary.byInst.map((row) => row.instId)).toEqual(['BTC-USDT-SWAP', 'ETH-USDT-SWAP'])
    expect(summary.byInst[0].count).toBe(2)
    expect(summary.byInst[0].netPnl).toBeCloseTo(1.83, 8)
    expect(summary.days.map((row) => row.day)).toEqual(['2023-11-14', '2023-11-15'])
    expect(summary.days[0].netPnl).toBeCloseTo(3.05, 8)
    expect(summary.days[1].netPnl).toBeCloseTo(-1.32, 8)
  })

  it('历史仓位会把资金费算进净收益，并按 uTime 归日', () => {
    const summary = ocSelectionSummary('positions', positions)

    expect(summary.count).toBe(1)
    expect(summary.realizedPnl).toBeCloseTo(-12.5, 8)
    expect(summary.fee).toBeCloseTo(-0.2, 8)
    expect(summary.fundingFee).toBeCloseTo(0.05, 8)
    expect(summary.netPnl).toBeCloseTo(-12.65, 8)
    expect(summary.winRate).toBe(0)
    expect(summary.days).toHaveLength(1)
    expect(summary.days[0].netPnl).toBeCloseTo(-12.65, 8)
  })

  it('交易账单按 fillTime 归日', () => {
    const summary = ocSelectionSummary('fills', fills)

    expect(summary.netPnl).toBeCloseTo(2.26, 8)
    expect(summary.days).toEqual([{ day: '2023-11-14', netPnl: 2.2600000000000002 }].map((row) => ({
      day: row.day,
      netPnl: expect.closeTo(row.netPnl, 8)
    })))
  })

  it('空选时所有金额为 0、胜率未知', () => {
    const summary = ocSelectionSummary('pending', [])

    expect(summary.count).toBe(0)
    expect(summary.netPnl).toBe(0)
    expect(summary.winRate).toBeNull()
    expect(summary.byInst).toEqual([])
    expect(summary.days).toEqual([])
  })
})

describe('ocRowKey', () => {
  it('委托用 ordId、仓位用合约+方向+开平时间、成交用 tradeId', () => {
    expect(ocRowKey('history', orders[0])).toBe('ord-1001')
    expect(ocRowKey('positions', positions[0])).toBe('pos-ETH-USDT-SWAP-short-1700000000000-1700000100000')
    expect(ocRowKey('fills', fills[0])).toBe('fill-T1')
  })
})

describe('ocSelectionPairs', () => {
  it('给出固定顺序的中文指标，净收益带正负号', () => {
    const pairs = ocSelectionPairs(ocSelectionSummary('history', orders))

    expect(pairs.map((pair) => pair.key)).toEqual(['已选笔数', '已实现盈亏', '手续费', '资金费', '净收益', '胜率'])
    expect(pairs[0].html).toContain('3')
    expect(pairs[4].html).toContain('1.73')
    expect(pairs[5].html).toContain('50.00%')
  })
})
