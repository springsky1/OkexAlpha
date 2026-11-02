/* ==========================================================================
   OkexAlpha 数据服务 · 静态后台管理脚本
   --------------------------------------------------------------------------
   约束：零依赖（无 CDN / 无 npm / 无构建），原生 ES2020 浏览器环境。
   同源相对路径：所有请求都走 /api/v1/... 与 /actuator/health，
   因此页面既可由 Spring Boot 直接托管（http://127.0.0.1:18080/），
   也可由任意静态服务器托管后反向代理到后端。

   分区索引：
     0. 常量与枚举
     1. 通用工具（格式化 / 转义 / DOM 占位）
     2. 请求封装（统一 ApiResponse 解析与错误提示）
     3. 全局交互（toast / 确认弹窗 / 快捷时间 / 忙碌态）
     4. 顶部栏（服务地址 / 健康徽标 / 最后刷新时间）
     5. hash 路由
     6. 运行状态页
     7. K 线查询页（含 canvas 蜡烛图）
     8. 资金费率页
     9. 统计查询页（4 个 tab）
    10. 补拉页（4 个表单）
    11. 作业列表页
    12. 同步页
    13. 数据浏览页（coins / instruments）
    14. 启动
   ========================================================================== */
'use strict';

/* ======================= 0. 常量与枚举 ======================= */

/* 换行 / 制表符常量（用于切分文本域内容，避免在源码里写转义字符） */
const NL = String.fromCharCode(10);
const TAB = String.fromCharCode(9);

/* Bar：后端枚举共 24 个，前后端必须完全一致 */
const BARS = [
  ['1m', '1m（1 分钟）'],
  ['3m', '3m（3 分钟）'],
  ['5m', '5m（5 分钟）'],
  ['15m', '15m（15 分钟）'],
  ['30m', '30m（30 分钟）'],
  ['1H', '1H（1 小时）'],
  ['2H', '2H（2 小时）'],
  ['4H', '4H（4 小时）'],
  ['6H', '6H（6 小时）'],
  ['12H', '12H（12 小时）'],
  ['1D', '1D（1 天）'],
  ['2D', '2D（2 天）'],
  ['3D', '3D（3 天）'],
  ['1W', '1W（1 周）'],
  ['1M', '1M（1 月）'],
  ['3M', '3M（3 月）'],
  ['6Hutc', '6Hutc（6 小时 · UTC）'],
  ['12Hutc', '12Hutc（12 小时 · UTC）'],
  ['1Dutc', '1Dutc（1 天 · UTC）'],
  ['2Dutc', '2Dutc（2 天 · UTC）'],
  ['3Dutc', '3Dutc（3 天 · UTC）'],
  ['1Wutc', '1Wutc（1 周 · UTC）'],
  ['1Mutc', '1Mutc（1 月 · UTC）'],
  ['3Mutc', '3Mutc（3 月 · UTC）']
];

/* KlineType：仅 CANDLE / MARK（INDEX 指数 K 线已下线） */
const KLINE_TYPES = [
  ['CANDLE', 'CANDLE（蜡烛 K 线，含成交量）'],
  ['MARK', 'MARK（标记价格）']
];

/* InstType：5 类 */
const INST_TYPES = [
  ['SPOT', 'SPOT（现货）'],
  ['MARGIN', 'MARGIN（杠杆）'],
  ['SWAP', 'SWAP（永续）'],
  ['FUTURES', 'FUTURES（交割）'],
  ['OPTION', 'OPTION（期权）']
];

/* Period：统计类接口只支持 5m / 1H / 1D */
const PERIODS = [
  ['5m', '5m'],
  ['1H', '1H'],
  ['1D', '1D']
];

/* RatioType：[code, 名称, scopeKind] */
const RATIO_TYPES = [
  ['LSR_ACCOUNT_CCY', '多空人数比（按币种）', 'CCY'],
  ['LSR_ACCOUNT_CONTRACT', '多空人数比（按合约）', 'INST'],
  ['TOP_TRADER_ACCOUNT', '精英账户多空比', 'INST'],
  ['TOP_TRADER_POSITION', '精英持仓多空比', 'INST']
];

/* DataType：作业列表过滤用 */
const DATA_TYPES = [
  'KLINE',
  'FUNDING_RATE',
  'LONG_SHORT_RATIO',
  'OPEN_INTEREST_VOLUME',
  'TAKER_VOLUME',
  'OPEN_INTEREST'
];

const DATA_TYPE_LABELS = {
  KLINE: 'K 线',
  FUNDING_RATE: '资金费率',
  LONG_SHORT_RATIO: '多空比',
  OPEN_INTEREST_VOLUME: '持仓量-成交额',
  TAKER_VOLUME: '主动买卖量',
  OPEN_INTEREST: '持仓量快照'
};

/* 作业状态：PENDING / RUNNING / PAUSED / DONE / FAILED / CANCELED */
const JOB_STATUSES = ['PENDING', 'RUNNING', 'PAUSED', 'DONE', 'FAILED', 'CANCELED'];

const JOB_STATUS_LABELS = {
  PENDING: '待执行',
  RUNNING: '执行中',
  PAUSED: '已暂停',
  DONE: '已完成',
  FAILED: '失败',
  CANCELED: '已取消'
};

const TASK_STATUS_LABELS = {
  PENDING: '待执行',
  RUNNING: '执行中',
  DONE: '已完成',
  FAILED: '失败',
  SKIPPED: '已跳过'
};

/* MarketStats 的 kind（补拉表单用） */
const STAT_KINDS = ['OPEN_INTEREST_VOLUME', 'TAKER_VOLUME', 'OPEN_INTEREST'];

/* 页面（hash 路由）：前 8 项属于「基础数据」分组，后 4 项属于「策略」分组（阶段 C 骨架） */
const PAGES = ['status', 'klines', 'funding', 'stats', 'backfill', 'jobs', 'sync', 'data',
  'strategy-backtest', 'shadow', 'health', 'live',
  'account', 'orders', 'strategies'];

const PAGE_TITLES = {
  status: '运行状态',
  klines: 'K 线查询',
  funding: '资金费率',
  stats: '统计查询',
  backfill: '补拉作业',
  jobs: '作业列表',
  sync: '同步',
  data: '数据浏览',
  'strategy-backtest': '策略回测',
  shadow: '影子订单',
  health: '健康度',
  live: '实盘',
  account: '账户概览',
  orders: '我的订单',
  strategies: '马丁策略'
};

/* 左侧导航分组：组名 -> 该组包含的路由（顺序即渲染顺序） */
const NAV_GROUPS = {
  basic: ['status', 'klines', 'funding', 'stats', 'backfill', 'jobs', 'sync', 'data'],
  strategy: ['strategy-backtest', 'shadow', 'health', 'live'],
  account: ['account', 'orders', 'strategies']
};

/* 分组中文标题（与 index.html 里的 .nav-group-title 文案一致） */
const NAV_GROUP_LABELS = { basic: '基础数据', strategy: '策略', account: '账户' };

/* 折叠状态存储键前缀：okx.nav.<group>，"1" 表示折叠，缺省为展开 */
const NAV_COLLAPSE_KEY = 'okx.nav.';

/* 图表字体与配色（与 styles.css 的 --up / --down 保持一致） */
const CHART_FONT = '11px ui-monospace, Menlo, Consolas, monospace';
const COLOR_UP = '#12a45c';
const COLOR_DOWN = '#e0533c';
const COLOR_GRID = '#e4e8ef';
const COLOR_AXIS = '#98a2b3';

/* ======================= 1. 通用工具 ======================= */

const ui = {
  route: '',
  timers: { status: 0, jobs: 0, bt: 0, shadow: 0, health: 0, live: 0 },
  statusBusy: false,
  klines: { loaded: false, rows: [], chartRows: [], chartInfo: null, lastQuery: null, chartRatio: null },
  funding: { loaded: false },
  stats: { loaded: {} },
  coins: { loaded: false, limit: 50, offset: 0 },
  inst: { loaded: false, limit: 50, offset: 0 },
  jobs: { loaded: false, limit: 50, offset: 0, expanded: {} },
  bt: {
    bound: false,
    loaded: false,
    runs: [],
    selectedId: 0,
    detail: null,
    results: [],
    best: null,
    resultTotal: 0,
    expanded: {},
    coins: {},
    champAggId: 0,
    champCoinId: 0,
    equity: [],
    trades: []
  },
  shadow: {
    bound: false,
    status: null,
    posRows: [],
    orderRows: [],
    pfRows: []
  },
  health: {
    bound: false,
    level: '',
    limit: 100,
    offset: 0,
    rows: [],
    latest: [],
    summary: null
  },
  live: {
    bound: false,
    gate: null,
    account: null,
    lastOrder: null
  },
  account: {
    bound: false,
    summary: null,
    balance: null,
    positions: null,
    loaded: false
  },
  orders: {
    bound: false,
    tab: 'local',
    loaded: { local: false, pending: false, history: false },
    loading: { local: false, pending: false, history: false }
  },
  strategies: {
    bound: false,
    tab: 'running',
    loaded: { running: false, history: false },
    loading: { running: false, history: false },
    rows: { running: [], history: [] },
    expandedId: 0,
    detail: null
  }
};

const nfInt = new Intl.NumberFormat('zh-CN');
const nfNum = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 8 });
const nfMoney = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 });

function byId(id) {
  return document.getElementById(id);
}

function isNil(v) {
  return v === null || v === undefined || v === '';
}

/* HTML 转义（不用正则，避免源码里出现反斜杠） */
function esc(v) {
  let s = isNil(v) ? '' : String(v);
  s = s.split('&').join('&amp;');
  s = s.split('<').join('&lt;');
  s = s.split('>').join('&gt;');
  s = s.split('"').join('&quot;');
  s = s.split("'").join('&#39;');
  return s;
}

function fmtInt(v) {
  if (isNil(v)) return '-';
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  return nfInt.format(n);
}

function fmtNum(v) {
  if (isNil(v)) return '-';
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  return nfNum.format(n);
}

function fmtMoney(v) {
  if (isNil(v)) return '-';
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  return nfMoney.format(n);
}

function fmtRatio(v) {
  if (isNil(v)) return '-';
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  return n.toFixed(4);
}

function fmtPct(v) {
  if (isNil(v)) return '-';
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  const sign = n > 0 ? '+' : '';
  return sign + n.toFixed(2) + '%';
}

function pad2(n) {
  return (n < 10 ? '0' : '') + n;
}

/* 毫秒时间戳 -> 本地时间 YYYY-MM-DD HH:mm:ss */
function fmtTime(ms) {
  const n = Number(ms);
  if (!isFinite(n) || n <= 0) return '-';
  const d = new Date(n);
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
    ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
}

/* 毫秒时间戳 -> 本地时间 MM-DD HH:mm（图表轴用） */
function fmtShortTime(ms) {
  const n = Number(ms);
  if (!isFinite(n) || n <= 0) return '-';
  const d = new Date(n);
  return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}

/* 毫秒时间戳 -> datetime-local 输入框需要的字符串 */
function toLocalInput(ms) {
  const d = new Date(ms);
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
    'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}

/* datetime-local 输入框 -> 毫秒时间戳（空值返回 null） */
function readTimeMs(input) {
  if (!input) return null;
  const v = String(input.value || '').trim();
  if (!v) return null;
  const t = new Date(v).getTime();
  return isFinite(t) ? t : null;
}

/* 后端时间字符串（可能带 6 位微秒）-> 毫秒，兼容小数位数不足/超额 */
function parseServerTime(s) {
  if (!s) return NaN;
  const t = String(s);
  const dot = t.indexOf('.');
  let out = t;
  if (dot >= 0) {
    let i = dot + 1;
    while (i < t.length && t.charAt(i) >= '0' && t.charAt(i) <= '9') i++;
    let frac = t.slice(dot + 1, i);
    if (frac.length > 3) frac = frac.slice(0, 3);
    while (frac.length < 3) frac = frac + '0';
    out = t.slice(0, dot) + '.' + frac + t.slice(i);
  }
  const ms = Date.parse(out);
  return isFinite(ms) ? ms : NaN;
}

function fmtDur(ms) {
  const n = Number(ms);
  if (!isFinite(n) || n < 0) return '-';
  if (n < 1000) return n + ' ms';
  const sec = n / 1000;
  if (sec < 60) return sec.toFixed(1) + ' 秒';
  const m = Math.floor(sec / 60);
  const s = Math.round(sec - m * 60);
  return m + ' 分 ' + s + ' 秒';
}

/* 文本域 / 逗号分隔输入 -> 去重数组 */
function parseList(text) {
  if (isNil(text)) return [];
  let s = String(text);
  s = s.split(NL).join(',');
  s = s.split(TAB).join(',');
  s = s.split(',').join(',');
  s = s.split(';').join(',');
  s = s.split('|').join(',');
  s = s.split('，').join(',');
  s = s.split('、').join(',');
  s = s.split(' ').join(',');
  const out = [];
  const parts = s.split(',');
  for (let i = 0; i < parts.length; i++) {
    const v = parts[i].trim();
    if (v && out.indexOf(v) < 0) out.push(v);
  }
  return out;
}

/* 拼接查询串，跳过 null / undefined / 空串 */
function buildQuery(params) {
  const sp = new URLSearchParams();
  if (params) {
    const keys = Object.keys(params);
    for (let i = 0; i < keys.length; i++) {
      const k = keys[i];
      const v = params[k];
      if (v === null || v === undefined) continue;
      const s = String(v).trim();
      if (s === '') continue;
      sp.append(k, s);
    }
  }
  const out = sp.toString();
  return out ? '?' + out : '';
}

/* 下拉框填充：[value, label] 数组 */
function fillSelect(sel, items, def) {
  if (!sel) return;
  sel.innerHTML = '';
  for (let i = 0; i < items.length; i++) {
    const it = items[i];
    const value = Array.isArray(it) ? it[0] : it;
    const label = Array.isArray(it) ? it[1] : it;
    const opt = document.createElement('option');
    opt.value = value;
    opt.textContent = label;
    if (def !== undefined && def !== null && value === def) {
      opt.selected = true;
      opt.defaultSelected = true;
    }
    sel.appendChild(opt);
  }
  if (def !== undefined && def !== null) sel.value = def;
}

/* 追加选项（保留已有项，如「全部」） */
function appendSelect(sel, items) {
  if (!sel) return;
  for (let i = 0; i < items.length; i++) {
    const it = items[i];
    const value = Array.isArray(it) ? it[0] : it;
    const label = Array.isArray(it) ? it[1] : it;
    const opt = document.createElement('option');
    opt.value = value;
    opt.textContent = label;
    sel.appendChild(opt);
  }
}

/* 复选框组取值 */
function checkedValues(group) {
  const box = document.querySelector('[data-group="' + group + '"]');
  if (!box) return [];
  const inputs = box.querySelectorAll('input[type="checkbox"]');
  const out = [];
  for (let i = 0; i < inputs.length; i++) {
    if (inputs[i].checked) out.push(inputs[i].value);
  }
  return out;
}

/* 单片复选/单选组的值 */
function radioValue(name) {
  const list = document.querySelectorAll('input[type="radio"][name="' + name + '"]');
  for (let i = 0; i < list.length; i++) {
    if (list[i].checked) return list[i].value;
  }
  return '';
}

/* ---------- 占位与状态块 ---------- */
function setLoading(node, text) {
  if (!node) return;
  node.innerHTML = '<div class="state"><span class="spinner"></span><span>' + esc(text || '加载中…') + '</span></div>';
}

function setEmpty(node, text) {
  if (!node) return;
  node.innerHTML = '<div class="state">' + esc(text || '暂无数据') + '</div>';
}

function setErrorState(node, text) {
  if (!node) return;
  node.innerHTML = '<div class="state is-error">' + esc(text || '加载失败') + '</div>';
}

/* 顶部/表单内的红色错误条 */
function showError(box, msg) {
  if (!box) return;
  if (msg) {
    box.textContent = msg;
    box.hidden = false;
  } else {
    box.textContent = '';
    box.hidden = true;
  }
}

/* 结果槽里的错误盒 */
function renderErrorBox(container, msg) {
  if (!container) return;
  container.innerHTML = '<div class="result-box is-err">' + esc(msg) + '</div>';
}

/* 状态徽标 */
function badge(status) {
  const s = isNil(status) ? 'UNKNOWN' : String(status);
  const label = JOB_STATUS_LABELS[s] || TASK_STATUS_LABELS[s] || s;
  return '<span class="badge" data-status="' + esc(s) + '">' + esc(label) + '</span>';
}

/* 通用表格渲染：cols = [{title, key, num, render(row) -> html}] */
function renderTable(container, cols, rows) {
  if (!container) return;
  const list = Array.isArray(rows) ? rows : [];
  if (!list.length) {
    setEmpty(container, '暂无数据');
    return;
  }
  let html = '<table><thead><tr>';
  for (let i = 0; i < cols.length; i++) {
    html += '<th' + (cols[i].num ? ' class="num"' : '') + '>' + esc(cols[i].title) + '</th>';
  }
  html += '</tr></thead><tbody>';
  for (let r = 0; r < list.length; r++) {
    const row = list[r];
    html += '<tr>';
    for (let c = 0; c < cols.length; c++) {
      const col = cols[c];
      let cell;
      if (typeof col.render === 'function') {
        cell = col.render(row);
      } else {
        cell = esc(isNil(row[col.key]) ? '-' : row[col.key]);
      }
      html += '<td' + (col.num ? ' class="num"' : '') + '>' + cell + '</td>';
    }
    html += '</tr>';
  }
  html += '</tbody></table>';
  container.innerHTML = html;
}

/* 分页文案 */
function pagerInfo(offset, count) {
  if (!count) return '暂无记录';
  return '第 ' + (offset + 1) + '–' + (offset + count) + ' 条';
}

/* 数字型输入校验：返回 undefined（未填）/ null（非法）/ 数值 */
function readIntField(el, min, max, label) {
  if (!el) return undefined;
  const raw = String(el.value || '').trim();
  if (!raw) return undefined;
  const n = Number(raw);
  if (!isFinite(n) || Math.floor(n) !== n || n < min || n > max) {
    toast((label || '数值') + ' 需为 ' + min + ' ~ ' + max + ' 的整数', 'err');
    return null;
  }
  return n;
}

/* ======================= 2. 请求封装 ======================= */

class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

/* 统一解析 {code:"0",msg:"",data:...}；code 非 "0" 抛错并带出 msg */
function unwrap(payload, status) {
  if (payload && typeof payload === 'object' && !Array.isArray(payload) && payload.code !== undefined) {
    if (String(payload.code) !== '0') {
      throw new ApiError(payload.msg || ('接口返回错误码 ' + payload.code), status, payload.code);
    }
    return payload.data;
  }
  return payload;
}

async function request(method, path, params, body) {
  const url = path + buildQuery(params);
  const init = { method: method, headers: { 'Accept': 'application/json' }, cache: 'no-store' };
  if (body !== undefined && body !== null) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(url, init);
  } catch (err) {
    throw new ApiError('无法连接后端服务（' + url + '），请确认服务已启动', 0, 'NETWORK');
  }
  const text = await res.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch (err) {
      payload = null;
    }
  }
  if (!res.ok) {
    const msg = payload && payload.msg ? payload.msg : ('HTTP ' + res.status + ' ' + (res.statusText || '请求失败'));
    throw new ApiError(msg, res.status, (payload && payload.code) || ('HTTP_' + res.status));
  }
  if (!payload) {
    throw new ApiError('响应不是合法的 JSON（HTTP ' + res.status + '）', res.status, 'BAD_JSON');
  }
  return unwrap(payload, res.status);
}

function apiGet(path, params) {
  return request('GET', path, params, null);
}

function apiPost(path, body, params) {
  return request('POST', path, params, body);
}

function apiRawGet(path) {
  return request('GET', path, null, null);
}

function touchRefresh() {
  const el = byId('last-refresh');
  if (el) el.textContent = fmtTime(Date.now());
}

/* ======================= 2.5 instId 下拉选择器 ======================= */

const instCache = { loaded: false, loading: null, list: [], ids: Object.create(null) };

/* 品种清单只拉一次（SWAP + FUTURES），按 instId 升序缓存 */
function loadInstCache() {
  if (instCache.loaded) return Promise.resolve(instCache.list);
  if (instCache.loading) return instCache.loading;
  instCache.loading = (async function () {
    const out = [];
    const types = ['SWAP', 'FUTURES'];
    for (let i = 0; i < types.length; i++) {
      try {
        const data = await apiGet('/api/v1/instruments', { instType: types[i], limit: 1000 });
        if (Array.isArray(data)) {
          for (let j = 0; j < data.length; j++) out.push(data[j]);
        }
      } catch (err) {
        /* 单个类型失败不影响其它类型 */
      }
    }
    out.sort(function (a, b) {
      const x = String(a.instId || '');
      const y = String(b.instId || '');
      return x < y ? -1 : (x > y ? 1 : 0);
    });
    const ids = Object.create(null);
    for (let k = 0; k < out.length; k++) ids[String(out[k].instId || '').toUpperCase()] = true;
    instCache.ids = ids;
    instCache.list = out;
    instCache.loaded = out.length > 0;
    return out;
  })().then(function (list) {
    instCache.loading = null;
    return list;
  }, function () {
    instCache.loading = null;
    return [];
  });
  return instCache.loading;
}

/* instId 第一段即 coin：BTC-USDT-SWAP → BTC */
function coinOfInst(instId) {
  const s = String(instId || '');
  const i = s.indexOf('-');
  return i > 0 ? s.slice(0, i) : s;
}

/* 给 input/textarea 挂 instId 下拉选择器
   opts.multiple  textarea 逗号列表模式（选中即追加，已选的从候选里剔除）
   opts.instTypes 只列这些品种类型，默认 ['SWAP','FUTURES']
   opts.maxRows   面板最多渲染行数，默认 300 */
function attachInstPicker(el, opts) {
  if (!el) return null;
  if (el.__instPicker) return el.__instPicker;
  const options = opts || {};
  const multiple = !!options.multiple;
  const types = options.instTypes && options.instTypes.length ? options.instTypes : ['SWAP', 'FUTURES'];
  const maxRows = options.maxRows || 300;

  const wrap = document.createElement('div');
  wrap.className = 'inst-combo';
  el.parentNode.insertBefore(wrap, el);
  wrap.appendChild(el);

  const caret = document.createElement('button');
  caret.type = 'button';
  caret.className = 'inst-caret';
  caret.tabIndex = -1;
  caret.setAttribute('aria-label', '展开 instId 候选');
  caret.textContent = '\u25be';
  wrap.appendChild(caret);

  const panel = document.createElement('div');
  panel.className = 'inst-panel';
  panel.hidden = true;
  const head = document.createElement('div');
  head.className = 'inst-panel-head';
  const listEl = document.createElement('div');
  listEl.className = 'inst-panel-list';
  listEl.setAttribute('role', 'listbox');
  panel.appendChild(head);
  panel.appendChild(listEl);
  wrap.appendChild(panel);

  let rows = [];
  let active = -1;

  /* 多选时只看最后一个逗号后的输入作为过滤词 */
  function queryOf() {
    const v = String(el.value || '');
    if (!multiple) return v.trim().toUpperCase();
    const i = v.lastIndexOf(',');
    return v.slice(i + 1).trim().toUpperCase();
  }

  function allowed(it) {
    return types.indexOf(String(it.instType || '')) >= 0;
  }

  function markActive() {
    const nodes = listEl.querySelectorAll('.inst-opt');
    for (let i = 0; i < nodes.length; i++) {
      const on = i === active;
      nodes[i].classList.toggle('is-active', on);
      if (on && nodes[i].scrollIntoView) nodes[i].scrollIntoView({ block: 'nearest' });
    }
  }

  function refresh() {
    const picked = multiple ? parseList(el.value) : [];
    let q = queryOf();
    /* 框里已经是一个完整 instId（刚选中/初始值）时不当作过滤词，直接列全部候选 */
    if (q && instCache.ids[q]) q = '';
    const pool = instCache.list.filter(allowed);
    rows = [];
    let matched = 0;
    for (let i = 0; i < pool.length; i++) {
      const it = pool[i];
      if (!it || !it.instId) continue;
      if (picked.indexOf(it.instId) >= 0) continue;
      const id = String(it.instId).toUpperCase();
      const coin = coinOfInst(it.instId).toUpperCase();
      if (q && id.indexOf(q) < 0 && coin.indexOf(q) < 0) continue;
      matched++;
      if (rows.length < maxRows) rows.push(it);
    }
    active = rows.length ? 0 : -1;
    if (!instCache.list.length) {
      head.textContent = '库内暂无品种';
      listEl.innerHTML = '<div class="inst-empty">库内还没有品种数据，请先到「同步」页执行 POST /api/v1/sync/instruments</div>';
      return;
    }
    const scope = types.join('+');
    head.textContent = q
      ? (scope + ' 匹配 ' + matched + ' 个' + (matched > rows.length ? '（仅显示前 ' + rows.length + '）' : '') + ' · coin/instId 过滤')
      : (scope + ' 共 ' + pool.length + ' 个 · 输入 coin/instId 过滤');
    if (!rows.length) {
      listEl.innerHTML = '<div class="inst-empty">没有匹配的 instId</div>';
      return;
    }
    let html = '';
    for (let i = 0; i < rows.length; i++) {
      const it = rows[i];
      html += '<button type="button" class="inst-opt" role="option" data-idx="' + i + '">' +
        '<span class="inst-opt-id">' + esc(it.instId) + '</span>' +
        '<span class="inst-opt-meta">' + esc(coinOfInst(it.instId)) + ' \u00b7 ' + esc(it.instType || '') +
        (it.state ? ' \u00b7 ' + esc(it.state) : '') + '</span>' +
        '</button>';
    }
    listEl.innerHTML = html;
    markActive();
  }

  function close() {
    if (panel.hidden) return;
    panel.hidden = true;
    rows = [];
    active = -1;
  }

  function apply(it) {
    if (!it) return;
    if (!multiple) {
      el.value = it.instId;
    } else {
      /* 只保留已存在的合法 instId，丢掉刚打的过滤片段，避免把 "BTC" 这类半截词当成合约 */
      let tokens = parseList(el.value);
      if (instCache.loaded) {
        tokens = tokens.filter(function (t) { return !!instCache.ids[String(t).toUpperCase()]; });
      }
      if (tokens.indexOf(it.instId) < 0) tokens.push(it.instId);
      el.value = tokens.join(', ');
    }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    /* 多选模式保持面板打开，便于连续挑多个 */
    if (multiple) refresh(); else close();
  }

  let blurTimer = null;

  async function open() {
    if (blurTimer) { clearTimeout(blurTimer); blurTimer = null; }
    if (!panel.hidden) return;
    panel.hidden = false;
    head.textContent = '加载中…';
    listEl.innerHTML = '';
    await loadInstCache();
    if (!panel.hidden) refresh();
  }

  function move(step) {
    if (!rows.length) return;
    active = (active + step + rows.length) % rows.length;
    markActive();
  }

  el.addEventListener('focus', function () {
    if (blurTimer) { clearTimeout(blurTimer); blurTimer = null; }
    open();
  });
  el.addEventListener('input', function () {
    if (panel.hidden) open(); else refresh();
  });
  el.addEventListener('keydown', function (e) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      if (panel.hidden) { open(); e.preventDefault(); return; }
      move(e.key === 'ArrowDown' ? 1 : -1);
      e.preventDefault();
      return;
    }
    if (e.key === 'Enter') {
      if (!panel.hidden) {
        /* 框里已经是合法 instId（含多选时最后一个完整 instId）→ 回车只收起，不覆盖用户输入 */
        const tokens = parseList(el.value);
        const tail = String(tokens.length ? tokens[tokens.length - 1] : '').toUpperCase();
        if (tail && instCache.ids[tail]) {
          close();
          e.preventDefault();
          return;
        }
        if (rows.length) {
          apply(rows[active < 0 ? 0 : active]);
          e.preventDefault();
        }
      }
      return;
    }
    if (e.key === 'Escape') close();
  });
  el.addEventListener('blur', function () {
    if (blurTimer) clearTimeout(blurTimer);
    blurTimer = setTimeout(function () { blurTimer = null; close(); }, 140);
  });
  caret.addEventListener('mousedown', function (e) { e.preventDefault(); });
  caret.addEventListener('click', function () {
    if (panel.hidden) open(); else close();
  });
  listEl.addEventListener('mousedown', function (e) { e.preventDefault(); });
  listEl.addEventListener('click', function (e) {
    const btn = e.target && e.target.closest ? e.target.closest('.inst-opt') : null;
    if (!btn) return;
    const idx = Number(btn.getAttribute('data-idx'));
    if (rows[idx]) apply(rows[idx]);
    else close();
  });

  el.__instPicker = {
    open: open,
    close: close,
    reload: function () {
      instCache.loaded = false;
      instCache.list = [];
      instCache.ids = Object.create(null);
      panel.hidden = true;
      return open();
    }
  };
  return el.__instPicker;
}

/* ======================= 3. 全局交互 ======================= */

function toast(msg, kind) {
  const stack = byId('toast-stack');
  if (!stack) return;
  const el = document.createElement('div');
  el.className = 'toast' + (kind ? ' is-' + kind : '');
  el.textContent = String(msg);
  stack.appendChild(el);
  setTimeout(function () {
    if (el.parentNode) el.parentNode.removeChild(el);
  }, 5600);
}

/* 确认弹窗（替代 alert），返回 Promise<boolean> */
function confirmDialog(title, bodyHtml, okText) {
  return new Promise(function (resolve) {
    const back = byId('modal');
    if (!back) {
      resolve(false);
      return;
    }
    byId('modal-title').textContent = title || '确认';
    byId('modal-body').innerHTML = bodyHtml || '';
    const ok = byId('modal-ok');
    const cancel = byId('modal-cancel');
    ok.textContent = okText || '确认';
    back.hidden = false;

    function cleanup(value) {
      back.hidden = true;
      ok.removeEventListener('click', onOk);
      cancel.removeEventListener('click', onCancel);
      back.removeEventListener('click', onBack);
      document.removeEventListener('keydown', onKey);
      resolve(value);
    }
    function onOk() { cleanup(true); }
    function onCancel() { cleanup(false); }
    function onBack(e) { if (e.target === back) cleanup(false); }
    function onKey(e) { if (e.key === 'Escape') cleanup(false); }

    ok.addEventListener('click', onOk);
    cancel.addEventListener('click', onCancel);
    back.addEventListener('click', onBack);
    document.addEventListener('keydown', onKey);
  });
}

function setBusy(btn, busy) {
  if (!btn) return;
  if (busy) {
    btn.setAttribute('aria-busy', 'true');
    btn.disabled = true;
  } else {
    btn.removeAttribute('aria-busy');
    btn.disabled = false;
  }
}

/* 表单内的快捷时间按钮：近 N 天 / 清空 */
function setQuickRange(form, days) {
  if (!form) return;
  const start = form.querySelector('[data-role="start"]');
  const end = form.querySelector('[data-role="end"]');
  const now = Date.now();
  if (end) end.value = toLocalInput(now);
  if (start) start.value = toLocalInput(now - days * 86400000);
  toast('已填充最近 ' + days + ' 天的时间范围', 'info');
}

function clearQuickRange(form) {
  if (!form) return;
  const start = form.querySelector('[data-role="start"]');
  const end = form.querySelector('[data-role="end"]');
  if (start) start.value = '';
  if (end) end.value = '';
}

/* 模式切换：按 radio 值显示/隐藏 data-mode-of 区块 */
function syncModeBlock(name) {
  const value = radioValue(name);
  const blocks = document.querySelectorAll('[data-mode-of="' + name + '"]');
  for (let i = 0; i < blocks.length; i++) {
    const b = blocks[i];
    if (b.getAttribute('data-mode-value') === value) {
      b.classList.remove('is-off');
    } else {
      b.classList.add('is-off');
    }
  }
}

/* ======================= 4. 顶部栏 ======================= */

async function checkHealth(showToast) {
  const badgeEl = byId('health-badge');
  const textEl = byId('health-text');
  const detailEl = byId('health-detail');
  if (badgeEl) badgeEl.setAttribute('data-state', 'unknown');
  if (textEl) textEl.textContent = '检测中…';
  try {
    const health = await apiRawGet('/actuator/health');
    const status = health && health.status ? String(health.status) : 'UNKNOWN';
    const up = status === 'UP';
    if (badgeEl) badgeEl.setAttribute('data-state', up ? 'up' : 'down');
    if (textEl) textEl.textContent = up ? '服务正常' : ('服务 ' + status);
    const comps = health && health.components ? health.components : {};
    const keys = Object.keys(comps);
    const parts = [];
    for (let i = 0; i < keys.length; i++) {
      const c = comps[keys[i]];
      parts.push(keys[i] + '=' + (c && c.status ? c.status : '?'));
    }
    if (!parts.length) parts.push('status=' + status);
    const line = parts.join(' · ');
    if (detailEl) detailEl.textContent = line;
    const card = byId('status-health');
    const sub = byId('status-health-sub');
    if (card) card.textContent = up ? 'UP' : status;
    if (sub) sub.textContent = line;
    if (showToast) toast(up ? '健康检查通过' : ('健康检查异常：' + status), up ? 'ok' : 'err');
    return up;
  } catch (err) {
    if (badgeEl) badgeEl.setAttribute('data-state', 'down');
    if (textEl) textEl.textContent = '服务不可用';
    if (detailEl) detailEl.textContent = err.message;
    const card = byId('status-health');
    const sub = byId('status-health-sub');
    if (card) card.textContent = 'DOWN';
    if (sub) sub.textContent = err.message;
    if (showToast) toast('健康检查失败：' + err.message, 'err');
    return false;
  }
}

function initTopbar() {
  const originEl = byId('svc-origin');
  if (originEl) {
    originEl.textContent = window.location.origin && window.location.origin !== 'null'
      ? window.location.origin
      : 'file://（需用 http 服务访问）';
  }
  const badgeEl = byId('health-badge');
  if (badgeEl) badgeEl.addEventListener('click', function () { checkHealth(true); });
  const btn = byId('btn-check-health');
  if (btn) btn.addEventListener('click', function () { checkHealth(true); });
}

/* ======================= 5. hash 路由 ======================= */

/* 路由所属分组；未知路由返回空串 */
function navGroupOf(route) {
  const groups = Object.keys(NAV_GROUPS);
  for (let i = 0; i < groups.length; i++) {
    if (NAV_GROUPS[groups[i]].indexOf(route) >= 0) return groups[i];
  }
  return '';
}

/* 读取分组折叠状态（localStorage 不可用时按展开处理） */
function isNavCollapsed(group) {
  try {
    return window.localStorage.getItem(NAV_COLLAPSE_KEY + group) === '1';
  } catch (e) {
    return false;
  }
}

/* 应用折叠状态；persist 为 true 时写回 localStorage（强制展开不写回，避免覆盖用户偏好） */
function setNavCollapsed(group, collapsed, persist) {
  const wrap = document.querySelector('.nav-group[data-group="' + group + '"]');
  if (!wrap) return;
  const items = wrap.querySelector('.nav-items');
  const toggle = wrap.querySelector('.nav-group-toggle');
  if (items) items.hidden = !!collapsed;
  if (toggle) toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
  if (collapsed) {
    wrap.classList.add('is-collapsed');
  } else {
    wrap.classList.remove('is-collapsed');
  }
  if (persist) {
    try {
      window.localStorage.setItem(NAV_COLLAPSE_KEY + group, collapsed ? '1' : '0');
    } catch (e) {
      /* 隐私模式等 localStorage 不可用时忽略 */
    }
  }
}

/* 恢复已保存的折叠状态，并绑定分组标题点击 */
function initNavGroups() {
  const toggles = document.querySelectorAll('.nav-group-toggle');
  for (let i = 0; i < toggles.length; i++) {
    const group = toggles[i].getAttribute('data-group-toggle');
    if (!group) continue;
    setNavCollapsed(group, isNavCollapsed(group), false);
    toggles[i].addEventListener('click', function () {
      const g = this.getAttribute('data-group-toggle');
      setNavCollapsed(g, !isNavCollapsed(g), true);
    });
  }
}

/* 当前路由所在分组强制展开，其余分组恢复用户偏好 */
function syncNavGroups(route) {
  const current = navGroupOf(route);
  const groups = Object.keys(NAV_GROUPS);
  for (let i = 0; i < groups.length; i++) {
    const g = groups[i];
    if (g === current) setNavCollapsed(g, false, false);
    else setNavCollapsed(g, isNavCollapsed(g), false);
  }
}

function readRoute() {
  let raw = String(window.location.hash || '');
  if (raw.indexOf('#/') === 0) {
    raw = raw.slice(2);
  } else if (raw.charAt(0) === '#') {
    raw = raw.slice(1);
  }
  const q = raw.indexOf('?');
  if (q >= 0) raw = raw.slice(0, q);
  raw = raw.trim();
  return PAGES.indexOf(raw) >= 0 ? raw : 'status';
}

function stopTimers() {
  if (ui.timers.status) {
    clearInterval(ui.timers.status);
    ui.timers.status = 0;
  }
  if (ui.timers.jobs) {
    clearInterval(ui.timers.jobs);
    ui.timers.jobs = 0;
  }
  if (ui.timers.bt) {
    clearInterval(ui.timers.bt);
    ui.timers.bt = 0;
  }
  if (ui.timers.shadow) {
    clearInterval(ui.timers.shadow);
    ui.timers.shadow = 0;
  }
  if (ui.timers.health) {
    clearInterval(ui.timers.health);
    ui.timers.health = 0;
  }
  if (ui.timers.live) {
    clearInterval(ui.timers.live);
    ui.timers.live = 0;
  }
}

function startStatusTimer() {
  if (ui.timers.status) {
    clearInterval(ui.timers.status);
    ui.timers.status = 0;
  }
  const auto = byId('status-auto');
  if (ui.route !== 'status' || !auto || !auto.checked) return;
  ui.timers.status = setInterval(function () {
    if (ui.route !== 'status') return;
    loadStatus(false);
    checkHealth(false);
  }, 5000);
}

function startJobsTimer() {
  if (ui.timers.jobs) {
    clearInterval(ui.timers.jobs);
    ui.timers.jobs = 0;
  }
  const auto = byId('jobs-auto');
  if (ui.route !== 'jobs' || !auto || !auto.checked) return;
  ui.timers.jobs = setInterval(function () {
    if (ui.route !== 'jobs') return;
    loadJobs(true);
  }, 5000);
}

function applyRoute() {
  const route = readRoute();
  ui.route = route;
  const sections = document.querySelectorAll('.page');
  for (let i = 0; i < sections.length; i++) {
    const sec = sections[i];
    sec.hidden = sec.getAttribute('data-page') !== route;
  }
  const navs = document.querySelectorAll('.nav-item');
  for (let i = 0; i < navs.length; i++) {
    const a = navs[i];
    if (a.getAttribute('data-route') === route) {
      a.classList.add('is-active');
    } else {
      a.classList.remove('is-active');
    }
  }
  document.title = (PAGE_TITLES[route] || '管理后台') + ' · OkexAlpha';
  syncNavGroups(route);
  stopTimers();
  checkHealth(false);
  onEnter(route);
  window.scrollTo(0, 0);
}

function onEnter(route) {
  if (route === 'status') {
    loadStatus(true);
    startStatusTimer();
  } else if (route === 'klines') {
    if (!ui.klines.loaded) {
      loadKlines();
    } else {
      drawChart(ui.klines.chartRows, ui.klines.chartInfo);
    }
  } else if (route === 'funding') {
    if (!ui.funding.loaded) loadFunding();
  } else if (route === 'stats') {
    const tab = activeStatsTab();
    loadStatsTab(tab);
  } else if (route === 'jobs') {
    loadJobs(false);
    startJobsTimer();
  } else if (route === 'data') {
    if (!ui.coins.loaded) loadCoins();
    if (!ui.inst.loaded) loadInstruments();
  } else if (route === 'strategy-backtest') {
    initStrategyBacktestPage();
  } else if (route === 'shadow') {
    initShadowPage();
  } else if (route === 'health') {
    initHealthPage();
  } else if (route === 'live') {
    initLivePage();
  } else if (route === 'account') {
    initAccountPage();
  } else if (route === 'orders') {
    initOrdersPage();
  } else if (route === 'strategies') {
    initStrategiesPage();
  }
}

function initRouter() {
  window.addEventListener('hashchange', applyRoute);
}

/* ======================= 6. 运行状态页 ======================= */

function renderStatusRows(rows) {
  const box = byId('status-rows');
  if (!box) return;
  const keys = rows && typeof rows === 'object' ? Object.keys(rows) : [];
  if (!keys.length) {
    setEmpty(box, '暂无数据');
    return;
  }
  let html = '<div class="kv-grid">';
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i];
    html += '<div class="kv"><span class="kv-key">' + esc(k) + '</span><span class="kv-val">' + fmtInt(rows[k]) + '</span></div>';
  }
  html += '</div>';
  box.innerHTML = html;
}

function renderStatusInstTypes(list) {
  const box = byId('status-inst-types');
  if (!box) return;
  const arr = Array.isArray(list) ? list : [];
  if (!arr.length) {
    setEmpty(box, '暂无数据');
    return;
  }
  let html = '<table><thead><tr><th>instType</th><th class="num">数量</th></tr></thead><tbody>';
  for (let i = 0; i < arr.length; i++) {
    const r = arr[i];
    const type = r.inst_type !== undefined ? r.inst_type : (r.instType !== undefined ? r.instType : r.type);
    const cnt = r.cnt !== undefined ? r.cnt : r.count;
    html += '<tr><td>' + esc(isNil(type) ? '-' : type) + '</td><td class="num">' + fmtInt(cnt) + '</td></tr>';
  }
  html += '</tbody></table>';
  box.innerHTML = html;
}

function renderStatusJobs(list) {
  const box = byId('status-jobs');
  if (!box) return;
  const arr = Array.isArray(list) ? list : [];
  if (!arr.length) {
    setEmpty(box, '暂无补拉作业');
    return;
  }
  let html = '<table><thead><tr><th>dataType</th><th>status</th><th class="num">作业数</th></tr></thead><tbody>';
  for (let i = 0; i < arr.length; i++) {
    const r = arr[i];
    const type = r.data_type !== undefined ? r.data_type : (r.dataType !== undefined ? r.dataType : r.type);
    html += '<tr><td>' + esc(isNil(type) ? '-' : type) + '</td><td>' + badge(r.status) + '</td><td class="num">' + fmtInt(r.jobs) + '</td></tr>';
  }
  html += '</tbody></table>';
  box.innerHTML = html;
}

function renderStatusCursors(cursors) {
  const box = byId('status-cursors');
  if (!box) return;
  const keys = cursors && typeof cursors === 'object' ? Object.keys(cursors) : [];
  if (!keys.length) {
    setEmpty(box, '暂无数据');
    return;
  }
  let html = '<div class="kv-grid">';
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i];
    const v = cursors[k];
    const text = isNil(v) ? '-' : (fmtTime(v) + '（' + fmtInt(v) + '）');
    html += '<div class="kv"><span class="kv-key">' + esc(k) + '</span><span class="kv-val">' + esc(text) + '</span></div>';
  }
  html += '</div>';
  box.innerHTML = html;
}

async function loadStatus(first) {
  // 5 秒轮询 + 后端慢查询时，重叠的请求会把 Hikari 连接堆满，前端先做单飞（single-flight）保护
  if (ui.statusBusy) return;
  ui.statusBusy = true;
  const errBox = byId('status-error');
  showError(errBox, '');
  if (first) {
    setLoading(byId('status-rows'), '加载中…');
    setLoading(byId('status-inst-types'), '加载中…');
    setLoading(byId('status-jobs'), '加载中…');
    setLoading(byId('status-cursors'), '加载中…');
  }
  try {
    const data = await apiGet('/api/v1/sync/status');
    const rows = data && data.rows ? data.rows : {};
    renderStatusRows(rows);
    renderStatusInstTypes(data && data.instrumentsByType);
    renderStatusJobs(data && data.backfillJobs);
    renderStatusCursors(data && data.cursors);
    const instTotal = byId('status-inst-total');
    const coinTotal = byId('status-coin-total');
    const klineTotal = byId('status-kline-total');
    if (instTotal) instTotal.textContent = fmtInt(rows.instruments);
    if (coinTotal) coinTotal.textContent = fmtInt(rows.coins);
    if (klineTotal) klineTotal.textContent = fmtInt(rows.klines);
    touchRefresh();
  } catch (err) {
    showError(errBox, '加载运行状态失败：' + err.message);
    if (first) {
      setErrorState(byId('status-rows'), '加载失败');
      setErrorState(byId('status-inst-types'), '加载失败');
      setErrorState(byId('status-jobs'), '加载失败');
      setErrorState(byId('status-cursors'), '加载失败');
    }
  } finally {
    ui.statusBusy = false;
  }
}

function initStatusPage() {
  const refresh = byId('status-refresh');
  if (refresh) refresh.addEventListener('click', function () { loadStatus(true); });
  const auto = byId('status-auto');
  if (auto) auto.addEventListener('change', startStatusTimer);
}

/* ======================= 7. K 线查询页 ======================= */

const KLINE_COLS = [
  { title: '时间', render: function (r) { return '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>'; } },
  { title: '开盘价', num: true, render: function (r) { return fmtNum(r.open); } },
  { title: '最高价', num: true, render: function (r) { return fmtNum(r.high); } },
  { title: '最低价', num: true, render: function (r) { return fmtNum(r.low); } },
  { title: '收盘价', num: true, render: function (r) { return fmtNum(r.close); } },
  {
    title: '涨跌幅',
    num: true,
    render: function (r) {
      const o = Number(r.open);
      const c = Number(r.close);
      if (!isFinite(o) || !isFinite(c) || o === 0) return '-';
      const pct = (c - o) / o * 100;
      const cls = pct > 0 ? 'danger-text' : '';
      return '<span class="' + cls + '">' + esc(fmtPct(pct)) + '</span>';
    }
  },
  { title: '成交量 vol', num: true, render: function (r) { return fmtNum(r.vol); } },
  { title: '成交额（计价币）', num: true, render: function (r) { return fmtNum(r.volCcyQuote); } },
  { title: '已确认', num: true, render: function (r) { return esc(isNil(r.confirm) ? '-' : (r.confirm ? '是' : '否')); } }
];

function renderKlineSummary(rows, meta, suffix) {
  const el = byId('kline-summary');
  if (!el) return;
  if (!rows || !rows.length) {
    el.textContent = '暂无数据';
    return;
  }
  el.textContent = '共 ' + rows.length + ' 条 · ' + (meta || '') + (suffix ? ' · ' + suffix : '');
}

/* ---------- 图表数据与叠加折线 ---------- */

const CHART_LINE_COLORS = { close: '#2f6fed', ma5: '#f5a524', ma20: '#8b5cf6', ma60: '#0ea5e9' };
const CHART_SERIES_KEYS = ['close', 'ma5', 'ma20', 'ma60'];
const CHART_PAGE_SIZE = 1000;
const CHART_MAX_POINTS = 30000;

/* 多空比叠加：三类 rubik 指标，走右轴（独立刻度），与蜡烛/价格折线不共享量纲 */
const CHART_RATIO_SERIES = [
  { key: 'ratioPos', ratioType: 'TOP_TRADER_POSITION', label: '精英持仓多空比' },
  { key: 'ratioAcc', ratioType: 'TOP_TRADER_ACCOUNT', label: '大户账户多空比' },
  { key: 'ratioLsr', ratioType: 'LSR_ACCOUNT_CONTRACT', label: '全账户多空比' }
];
const CHART_RATIO_COLORS = { ratioPos: '#0f766e', ratioAcc: '#c2410c', ratioLsr: '#db2777' };
const CHART_RATIO_PAGE = 1000;

function chartLineColor(key) {
  return CHART_LINE_COLORS[key] || CHART_RATIO_COLORS[key] || COLOR_AXIS;
}

function chartOptsEnabled() {
  const out = {};
  for (let i = 0; i < CHART_SERIES_KEYS.length; i++) {
    const key = CHART_SERIES_KEYS[i];
    const el = byId('chart-opt-' + key);
    out[key] = !!(el && el.checked);
  }
  return out;
}

function chartRatioOptsEnabled() {
  const out = {};
  for (let i = 0; i < CHART_RATIO_SERIES.length; i++) {
    const key = CHART_RATIO_SERIES[i].key;
    const el = byId('chart-opt-' + key);
    out[key] = !!(el && el.checked);
  }
  return out;
}

function anyRatioOptEnabled() {
  const opts = chartRatioOptsEnabled();
  for (const key in opts) {
    if (opts[key]) return true;
  }
  return false;
}

/* bar -> 多空比周期：rubik 只提供 5m / 1H / 1D，取能覆盖该 bar 的最小周期 */
function ratioPeriodOfBar(bar) {
  const b = String(bar || '').replace(/utc$/i, '');
  const unit = b.charAt(b.length - 1);
  if (unit === 'm') return '5m';
  if (unit === 'H') return '1H';
  return '1D';
}

/* 拉取一条多空比序列（分页，每页 1000 点），返回 [[tsMs, ratio], ...] 升序 */
async function loadRatioPoints(ratioType, scope, period, startMs, endMs) {
  const points = [];
  let cursor = (startMs === null || startMs === undefined) ? null : startMs;
  for (let page = 0; page < 40; page++) {
    const params = {
      ratioType: ratioType,
      scope: scope,
      period: period,
      limit: CHART_RATIO_PAGE,
      order: 'asc'
    };
    if (cursor !== null) params.startMs = cursor;
    if (endMs !== null && endMs !== undefined) params.endMs = endMs;
    let batch;
    try {
      batch = await apiGet('/api/v1/ratios/long-short', params);
    } catch (e) {
      break;
    }
    const list = Array.isArray(batch) ? batch : [];
    if (!list.length) break;
    for (let i = 0; i < list.length; i++) {
      const ts = Number(list[i].tsMs);
      const v = Number(list[i].longShortRatio);
      if (isFinite(ts) && isFinite(v)) points.push([ts, v]);
    }
    const lastTs = Number(list[list.length - 1].tsMs);
    if (!isFinite(lastTs)) break;
    if (list.length < CHART_RATIO_PAGE) break;
    if (endMs !== null && endMs !== undefined && lastTs >= endMs) break;
    cursor = lastTs + 1;
  }
  points.sort(function (a, b) { return a[0] - b[0]; });
  return points;
}

async function loadRatioSeries(instId, bar, startMs, endMs) {
  const period = ratioPeriodOfBar(bar);
  const coin = String(instId || '').split('-')[0];
  const series = {};
  for (let i = 0; i < CHART_RATIO_SERIES.length; i++) {
    const spec = CHART_RATIO_SERIES[i];
    const scope = spec.ratioType === 'LSR_ACCOUNT_CCY' ? coin : instId;
    series[spec.key] = await loadRatioPoints(spec.ratioType, scope, period, startMs, endMs);
  }
  return { instId: instId, bar: bar, period: period, startMs: startMs, endMs: endMs, series: series };
}

/* 勾选了多空比才去拉数据；同一查询条件命中缓存则直接复用 */
async function ensureChartRatio(instId, bar, startMs, endMs) {
  if (!anyRatioOptEnabled()) {
    ui.klines.chartRatio = null;
    return null;
  }
  const s = (startMs === undefined) ? null : startMs;
  const e = (endMs === undefined) ? null : endMs;
  const cached = ui.klines.chartRatio;
  if (cached && cached.instId === instId && cached.bar === bar && cached.startMs === s && cached.endMs === e) {
    return cached;
  }
  const hintEl = byId('kline-chart-hint');
  if (hintEl) hintEl.textContent = '正在加载多空比数据…';
  const loaded = await loadRatioSeries(instId, bar, s, e);
  ui.klines.chartRatio = loaded;
  return loaded;
}

/* 简单移动平均，不足窗口期为 null */
function movingAverage(values, window) {
  const out = new Array(values.length);
  let sum = 0;
  for (let i = 0; i < values.length; i++) {
    const v = values[i];
    if (v === null || !isFinite(v)) {
      out[i] = null;
      continue;
    }
    sum += v;
    if (i >= window) {
      const drop = values[i - window];
      if (drop !== null && isFinite(drop)) sum -= drop;
    }
    out[i] = i >= window - 1 ? sum / window : null;
  }
  return out;
}

/* 点数多于像素列时按列聚合 OHLC（开=首根开，收=末根收，高=最高，低=最低） */
function aggregateCandles(list, maxCols) {
  if (list.length <= maxCols) return { rows: list, groupSize: 1 };
  const groupSize = Math.ceil(list.length / maxCols);
  const rows = [];
  for (let i = 0; i < list.length; i += groupSize) {
    const chunk = list.slice(i, i + groupSize);
    let hi = -Infinity;
    let lo = Infinity;
    let open = null;
    let close = null;
    let vol = 0;
    for (let j = 0; j < chunk.length; j++) {
      const r = chunk[j];
      const o = Number(r.open);
      const c = Number(r.close);
      const h = Number(r.high);
      const l = Number(r.low);
      if (open === null && isFinite(o)) open = o;
      if (isFinite(c)) close = c;
      if (isFinite(h) && h > hi) hi = h;
      if (isFinite(l) && l < lo) lo = l;
      vol += Number(r.vol) || 0;
    }
    rows.push({
      tsMs: Number(chunk[0].tsMs),
      open: open,
      close: close,
      high: hi,
      low: lo,
      vol: vol,
      merged: chunk.length
    });
  }
  return { rows: rows, groupSize: groupSize };
}

/* 按选中区间把整段 K 线读回来：每页 1000 条、按 ts 往前翻，直到覆盖 startMs 或读尽 */
async function loadChartSeries(base, fallbackRows) {
  const startMs = base.startMs;
  if (startMs === null || startMs === undefined) {
    const rows = (fallbackRows || []).slice().sort(function (a, b) { return Number(a.tsMs) - Number(b.tsMs); });
    return { rows: rows, truncated: false };
  }
  const seen = {};
  const rows = [];
  let cursorEnd = (base.endMs === null || base.endMs === undefined) ? null : base.endMs;
  let truncated = false;
  for (let page = 0; page < 60; page++) {
    const params = {
      instId: base.instId,
      bar: base.bar,
      klineType: base.klineType,
      limit: CHART_PAGE_SIZE,
      order: 'desc',
      startMs: startMs
    };
    if (cursorEnd !== null) params.endMs = cursorEnd;
    const batch = await apiGet('/api/v1/klines', params);
    const list = Array.isArray(batch) ? batch : [];
    if (!list.length) break;
    let minTs = Infinity;
    for (let i = 0; i < list.length; i++) {
      const r = list[i];
      const ts = Number(r.tsMs);
      if (!isFinite(ts)) continue;
      if (ts < minTs) minTs = ts;
      if (!seen[ts]) {
        seen[ts] = 1;
        rows.push(r);
      }
    }
    if (rows.length >= CHART_MAX_POINTS) { truncated = true; break; }
    if (!isFinite(minTs) || minTs <= startMs) break;
    if (list.length < CHART_PAGE_SIZE) break;
    cursorEnd = minTs - 1;
  }
  rows.sort(function (a, b) { return Number(a.tsMs) - Number(b.tsMs); });
  if (rows.length > CHART_MAX_POINTS) {
    rows.splice(0, rows.length - CHART_MAX_POINTS);
    truncated = true;
  }
  return { rows: rows, truncated: truncated };
}

function drawChart(rows, info) {
  const canvas = byId('kline-chart');
  if (!canvas) return;
  const hintEl = byId('kline-chart-hint');
  const wrap = canvas.parentNode;
  const w = Math.max(320, wrap.clientWidth || 600);
  const h = Math.max(200, wrap.clientHeight || 340);
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  canvas.style.width = w + 'px';
  canvas.style.height = h + 'px';
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);

  const list = Array.isArray(rows) ? rows : [];
  if (!list.length) {
    if (hintEl) hintEl.textContent = '';
    ctx.fillStyle = COLOR_AXIS;
    ctx.font = '12px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('暂无数据', w / 2, h / 2);
    return;
  }

  /* 多空比叠加：只有勾选且数据就绪才画；右轴需要额外的刻度空间 */
  const ratioOpts = chartRatioOptsEnabled();
  const ratioCache = ui.klines.chartRatio;
  const ratioSeries = [];
  if (ratioCache && ratioCache.series) {
    for (let i = 0; i < CHART_RATIO_SERIES.length; i++) {
      const spec = CHART_RATIO_SERIES[i];
      if (!ratioOpts[spec.key]) continue;
      const pts = ratioCache.series[spec.key] || [];
      if (pts.length) ratioSeries.push({ key: spec.key, label: spec.label, points: pts });
    }
  }
  const hasRatio = ratioSeries.length > 0;

  const padL = 66;
  const padR = hasRatio ? 56 : 14;
  const padT = 12;
  const padB = 26;
  const plotW = Math.max(20, w - padL - padR);
  const plotH = Math.max(20, h - padT - padB);

  /* 数据点比像素列还多时按列聚合 OHLC，保证选中区间的整段都画得下 */
  const agg = aggregateCandles(list, Math.max(40, Math.floor(plotW)));
  const cols = agg.rows;

  let min = Infinity;
  let max = -Infinity;
  for (let i = 0; i < cols.length; i++) {
    const lo = Number(cols[i].low);
    const hi = Number(cols[i].high);
    if (isFinite(lo) && lo < min) min = lo;
    if (isFinite(hi) && hi > max) max = hi;
  }
  if (!isFinite(min) || !isFinite(max)) {
    ctx.fillStyle = COLOR_AXIS;
    ctx.font = '12px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('数据缺少 OHLC 字段', w / 2, h / 2);
    return;
  }
  if (max === min) {
    max = min + 1;
    min = min - 1;
  }
  const pad = (max - min) * 0.06;
  min -= pad;
  max += pad;

  function yOf(v) {
    return padT + plotH - (v - min) / (max - min) * plotH;
  }

  /* 网格与价格轴 */
  const gridN = 5;
  ctx.font = CHART_FONT;
  ctx.lineWidth = 1;
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (let i = 0; i <= gridN; i++) {
    const v = min + (max - min) * i / gridN;
    const y = Math.round(yOf(v)) + 0.5;
    ctx.strokeStyle = COLOR_GRID;
    ctx.beginPath();
    ctx.moveTo(padL, y);
    ctx.lineTo(padL + plotW, y);
    ctx.stroke();
    ctx.fillStyle = COLOR_AXIS;
    let label;
    if (Math.abs(v) >= 1000) {
      label = nfInt.format(Math.round(v));
    } else if (Math.abs(v) >= 1) {
      label = v.toFixed(2);
    } else {
      label = v.toFixed(6);
    }
    ctx.fillText(label, padL - 8, y);
  }

  /* 蜡烛 */
  const slot = plotW / cols.length;
  let bodyW = Math.min(14, slot * 0.68);
  if (bodyW < 1) bodyW = 1;
  ctx.lineWidth = 1;
  for (let i = 0; i < cols.length; i++) {
    const r = cols[i];
    const o = Number(r.open);
    const c = Number(r.close);
    const hi = Number(r.high);
    const lo = Number(r.low);
    if (!isFinite(o) || !isFinite(c) || !isFinite(hi) || !isFinite(lo)) continue;
    const cx = padL + slot * (i + 0.5);
    const up = c >= o;
    const color = up ? COLOR_UP : COLOR_DOWN;
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    const x = Math.round(cx) + 0.5;
    ctx.beginPath();
    ctx.moveTo(x, yOf(hi));
    ctx.lineTo(x, yOf(lo));
    ctx.stroke();
    const yOpen = yOf(o);
    const yClose = yOf(c);
    const top = Math.min(yOpen, yClose);
    const height = Math.max(1, Math.abs(yClose - yOpen));
    ctx.fillRect(Math.round(cx - bodyW / 2), Math.round(top), Math.max(1, Math.round(bodyW)), Math.round(height));
  }

  /* 叠加折线：收盘价 / MA5 / MA20 / MA60 */
  const closes = [];
  for (let i = 0; i < cols.length; i++) {
    const c = Number(cols[i].close);
    closes.push(isFinite(c) ? c : null);
  }
  const opts = chartOptsEnabled();
  const lines = [];
  if (opts.close) lines.push({ key: 'close', values: closes });
  if (opts.ma5) lines.push({ key: 'ma5', values: movingAverage(closes, 5) });
  if (opts.ma20) lines.push({ key: 'ma20', values: movingAverage(closes, 20) });
  if (opts.ma60) lines.push({ key: 'ma60', values: movingAverage(closes, 60) });
  for (let s = 0; s < lines.length; s++) {
    const values = lines[s].values;
    ctx.strokeStyle = chartLineColor(lines[s].key);
    ctx.lineWidth = 1.6;
    ctx.beginPath();
    let started = false;
    for (let i = 0; i < values.length; i++) {
      const v = values[i];
      if (v === null || !isFinite(v)) {
        started = false;
        continue;
      }
      const x = padL + slot * (i + 0.5);
      const y = yOf(v);
      if (!started) {
        ctx.moveTo(x, y);
        started = true;
      } else {
        ctx.lineTo(x, y);
      }
    }
    ctx.stroke();
  }
  ctx.lineWidth = 1;

  /* 多空比折线：右轴独立刻度，附 1.0 多空均衡基准虚线 */
  if (hasRatio) {
    const tsList = [];
    for (let i = 0; i < list.length; i++) tsList.push(Number(list[i].tsMs));
    const firstTs = tsList[0];
    const lastTs = tsList[tsList.length - 1];

    function indexOfTs(ts) {
      if (ts <= tsList[0]) return 0;
      if (ts >= tsList[tsList.length - 1]) return tsList.length - 1;
      let lo = 0;
      let hi = tsList.length - 1;
      while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (tsList[mid] < ts) lo = mid + 1; else hi = mid;
      }
      return lo;
    }

    let rmin = Infinity;
    let rmax = -Infinity;
    for (let s = 0; s < ratioSeries.length; s++) {
      const pts = ratioSeries[s].points;
      for (let i = 0; i < pts.length; i++) {
        const ts = pts[i][0];
        if (ts < firstTs || ts > lastTs) continue;
        const v = pts[i][1];
        if (v < rmin) rmin = v;
        if (v > rmax) rmax = v;
      }
    }
    if (!isFinite(rmin) || !isFinite(rmax)) {
      rmin = 0.8;
      rmax = 1.2;
    }
    if (rmin > 1) rmin = 1;
    if (rmax < 1) rmax = 1;
    if (rmax - rmin < 0.05) {
      rmin -= 0.05;
      rmax += 0.05;
    }
    const rpad = (rmax - rmin) * 0.08;
    rmin -= rpad;
    rmax += rpad;

    function ryOf(v) {
      return padT + plotH - (v - rmin) / (rmax - rmin) * plotH;
    }

    const yOne = ryOf(1);
    if (yOne >= padT && yOne <= padT + plotH) {
      ctx.setLineDash([4, 4]);
      ctx.strokeStyle = '#c8cfdb';
      ctx.beginPath();
      ctx.moveTo(padL, yOne);
      ctx.lineTo(padL + plotW, yOne);
      ctx.stroke();
      ctx.setLineDash([]);
    }

    for (let s = 0; s < ratioSeries.length; s++) {
      const pts = ratioSeries[s].points;
      ctx.strokeStyle = chartLineColor(ratioSeries[s].key);
      ctx.lineWidth = 1.6;
      ctx.beginPath();
      let started = false;
      for (let i = 0; i < pts.length; i++) {
        const ts = pts[i][0];
        if (ts < firstTs || ts > lastTs) continue;
        const idx = indexOfTs(ts);
        const col = Math.min(cols.length - 1, Math.floor(idx / agg.groupSize));
        const x = padL + slot * (col + 0.5);
        const y = ryOf(pts[i][1]);
        if (!started) {
          ctx.moveTo(x, y);
          started = true;
        } else {
          ctx.lineTo(x, y);
        }
      }
      ctx.stroke();
    }
    ctx.lineWidth = 1;

    /* 右轴刻度（多空比量纲） */
    ctx.fillStyle = COLOR_AXIS;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    const ticks = 3;
    for (let i = 0; i < ticks; i++) {
      const v = rmin + (rmax - rmin) * i / (ticks - 1);
      const y = Math.min(padT + plotH - 6, Math.max(padT + 6, ryOf(v)));
      ctx.fillText(v.toFixed(2), padL + plotW + 8, y);
    }
    /* 右轴标题（竖排），与左轴价格区分 */
    ctx.save();
    ctx.translate(padL + plotW + padR - 6, padT + plotH / 2);
    ctx.rotate(-Math.PI / 2);
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = '#7a8496';
    ctx.fillText('多空比', 0, 0);
    ctx.restore();
  }

  /* 时间轴 */
  ctx.fillStyle = COLOR_AXIS;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  const labelN = Math.min(6, cols.length);
  for (let i = 0; i < labelN; i++) {
    const idx = labelN === 1 ? 0 : Math.round((cols.length - 1) * i / (labelN - 1));
    const cx = padL + slot * (idx + 0.5);
    const clamped = Math.min(padL + plotW - 34, Math.max(padL + 34, cx));
    ctx.fillText(fmtShortTime(cols[idx].tsMs), clamped, padT + plotH + 8);
  }

  /* 左上角说明 */
  ctx.textAlign = 'left';
  ctx.textBaseline = 'top';
  ctx.fillStyle = COLOR_AXIS;
  ctx.fillText('共 ' + list.length + ' 根' + (agg.groupSize > 1 ? '（' + cols.length + ' 列）' : ''), padL + 2, padT + 2);

  /* 折线图例 + 区间信息 */
  if (hintEl) {
    const names = [];
    if (opts.close) names.push('收盘价');
    if (opts.ma5) names.push('MA5');
    if (opts.ma20) names.push('MA20');
    if (opts.ma60) names.push('MA60');
    const parts = [];
    parts.push('区间 ' + fmtTime(list[0].tsMs) + ' → ' + fmtTime(list[list.length - 1].tsMs));
    parts.push('共 ' + list.length + ' 根');
    if (agg.groupSize > 1) parts.push('按 ' + agg.groupSize + ' 根/列聚合为 ' + cols.length + ' 列');
    if (info && info.truncated) parts.push('超出上限，仅显示最近 ' + list.length + ' 根');
    parts.push('折线：' + (names.length ? names.join(' / ') : '未选择'));
    const ratioNames = [];
    for (let i = 0; i < ratioSeries.length; i++) ratioNames.push(ratioSeries[i].label);
    if (anyRatioOptEnabled()) {
      if (ratioNames.length) {
        parts.push('多空比(右轴 ' + ((ratioCache && ratioCache.period) || '') + ')：' + ratioNames.join(' / '));
      } else if (ratioCache) {
        parts.push('多空比：库内该区间无数据（可先补拉 ratios）');
      } else {
        parts.push('多空比：尚未加载');
      }
    }
    hintEl.textContent = parts.join(' · ');
  }
}

function renderKlines(rows, klineType, meta, summarySuffix) {
  const list = Array.isArray(rows) ? rows : [];
  const wrap = byId('kline-table-wrap');
  const chartMeta = byId('kline-chart-meta');
  if (chartMeta) {
    chartMeta.textContent = list.length
      ? ('共 ' + list.length + ' 根 · ' + (klineType === 'MARK' ? '标记价格（无成交量字段）' : '蜡烛 K 线') + ' · 绿涨红跌')
      : '暂无数据';
  }
  renderTable(wrap, KLINE_COLS, list);
  renderKlineSummary(list, meta, summarySuffix);
  /* 图表需要时间升序；真正的整段数据在 loadChartSeries 里单独读取 */
  const asc = list.slice();
  asc.sort(function (a, b) { return Number(a.tsMs) - Number(b.tsMs); });
  ui.klines.chartRows = asc;
  ui.klines.chartInfo = null;
}

async function loadKlines() {
  const errBox = byId('kline-error');
  showError(errBox, '');
  const instId = String((byId('kline-inst-id') || {}).value || '').trim();
  if (!instId) {
    showError(errBox, '请填写 instId（例如 BTC-USDT-SWAP，只支持永续合约）');
    toast('请填写 instId', 'err');
    return;
  }
  const params = {
    instId: instId,
    bar: (byId('kline-bar') || {}).value,
    klineType: (byId('kline-type') || {}).value || 'CANDLE',
    order: (byId('kline-order') || {}).value || 'desc'
  };
  const limit = readIntField(byId('kline-limit'), 1, 5000, 'limit');
  if (limit === null) return;
  if (limit !== undefined) params.limit = limit;
  const startMs = readTimeMs(byId('kline-start'));
  const endMs = readTimeMs(byId('kline-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    showError(errBox, '开始时间必须早于结束时间');
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) params.startMs = startMs;
  if (endMs !== null) params.endMs = endMs;
  ui.klines.lastQuery = { instId: instId, bar: params.bar, startMs: startMs, endMs: endMs };

  const btn = byId('kline-submit');
  const wrap = byId('kline-table-wrap');
  setBusy(btn, true);
  setLoading(wrap, '查询中…');
  try {
    const data = await apiGet('/api/v1/klines', params);
    const list = Array.isArray(data) ? data : [];
    ui.klines.loaded = true;
    const meta = instId + ' · ' + params.bar + ' · ' + params.klineType;
    /* 表格按 limit 取一页；返回条数等于 limit 时提示一次，避免误以为区间数据缺失 */
    const cappedBy = params.limit && list.length >= params.limit ? params.limit : 0;
    renderKlines(list, params.klineType, meta,
      cappedBy ? '表格已满 limit=' + cappedBy + '（仅一页），完整区间见上方图表' : '');
    if (!list.length) toast('查询成功，但没有匹配的数据', 'info');

    /* 图表按"选中时间范围"整段取数：表格只按 limit 取一页，图表不受 limit 限制 */
    const hintEl = byId('kline-chart-hint');
    if (hintEl && startMs !== null) hintEl.textContent = '正在加载 ' + params.bar + ' 区间数据…';
    try {
      const series = await loadChartSeries({
        instId: instId,
        bar: params.bar,
        klineType: params.klineType,
        startMs: startMs,
        endMs: endMs
      }, list);
      ui.klines.chartRows = series.rows;
      ui.klines.chartInfo = { truncated: series.truncated };
      await ensureChartRatio(instId, params.bar, startMs, endMs);
      drawChart(series.rows, ui.klines.chartInfo);
      if (series.truncated) toast('区间数据超过 ' + CHART_MAX_POINTS + ' 根，图表只显示最近的部分', 'info');
    } catch (chartErr) {
      ui.klines.chartInfo = null;
      drawChart(ui.klines.chartRows, null);
      toast('图表数据加载失败：' + chartErr.message, 'err');
    }
    touchRefresh();
  } catch (err) {
    setErrorState(wrap, '查询失败：' + err.message);
    showError(errBox, err.message);
    toast('K 线查询失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

function initKlinePage() {
  fillSelect(byId('kline-bar'), BARS, '1H');
  fillSelect(byId('kline-type'), KLINE_TYPES, 'CANDLE');
  attachInstPicker(byId('kline-inst-id'), { instTypes: ['SWAP'] });
  const form = byId('form-kline');
  if (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      loadKlines();
    });
  }
  const reset = byId('kline-reset');
  if (reset) {
    reset.addEventListener('click', function () {
      if (form) form.reset();
      showError(byId('kline-error'), '');
      setEmpty(byId('kline-table-wrap'), '尚未查询');
      const summary = byId('kline-summary');
      if (summary) summary.textContent = '尚未查询';
      ui.klines.chartRows = [];
      ui.klines.chartInfo = null;
      ui.klines.loaded = false;
      ui.klines.lastQuery = null;
      ui.klines.chartRatio = null;
      for (let i = 0; i < CHART_RATIO_SERIES.length; i++) {
        const opt = byId('chart-opt-' + CHART_RATIO_SERIES[i].key);
        if (opt) opt.checked = false;
      }
      const chartMeta = byId('kline-chart-meta');
      if (chartMeta) chartMeta.textContent = '尚未查询';
      const hint = byId('kline-chart-hint');
      if (hint) hint.textContent = '';
      drawChart([], null);
      toast('已重置 K 线查询条件', 'info');
    });
  }

  /* 叠加折线：图例色块按颜色表上色，勾选变化立即重绘 */
  const swatches = document.querySelectorAll('.line-swatch');
  for (let i = 0; i < swatches.length; i++) {
    swatches[i].style.background = chartLineColor(swatches[i].getAttribute('data-key'));
  }
  for (let i = 0; i < CHART_SERIES_KEYS.length; i++) {
    const el = byId('chart-opt-' + CHART_SERIES_KEYS[i]);
    if (el) {
      el.addEventListener('change', function () {
        drawChart(ui.klines.chartRows, ui.klines.chartInfo);
      });
    }
  }
  /* 多空比勾选：首次勾选时按当前查询条件拉取，之后走缓存 */
  for (let i = 0; i < CHART_RATIO_SERIES.length; i++) {
    const key = CHART_RATIO_SERIES[i].key;
    const el = byId('chart-opt-' + key);
    if (!el) continue;
    el.addEventListener('change', async function () {
      const q = ui.klines.lastQuery;
      if (!q) {
        this.checked = false;
        toast('请先查询 K 线，再叠加多空比', 'info');
        return;
      }
      try {
        await ensureChartRatio(q.instId, q.bar, q.startMs, q.endMs);
      } catch (err) {
        toast('多空比加载失败：' + err.message, 'err');
      }
      drawChart(ui.klines.chartRows, ui.klines.chartInfo);
    });
  }
}

/* ======================= 8. 资金费率页 ======================= */

const FUNDING_COLS = [
  { title: '资金费率时间', render: function (r) { return '<span class="mono">' + esc(fmtTime(r.fundingTimeMs)) + '</span>'; } },
  { title: 'instId', key: 'instId' },
  { title: '资金费率', num: true, render: function (r) { return fmtNum(r.fundingRate); } },
  { title: '实际费率', num: true, render: function (r) { return fmtNum(r.realizedRate); } },
  { title: '溢价 premium', num: true, render: function (r) { return fmtNum(r.premium); } },
  { title: '利率 interestRate', num: true, render: function (r) { return fmtNum(r.interestRate); } },
  { title: '下次结算', render: function (r) { return '<span class="mono">' + esc(fmtTime(r.nextFundingTimeMs)) + '</span>'; } },
  { title: 'settled', num: true, render: function (r) { return esc(isNil(r.settled) ? '-' : (r.settled ? '是' : '否')); } },
  { title: 'method', render: function (r) { return esc(isNil(r.method) ? '-' : r.method); } },
  { title: 'formulaType', render: function (r) { return esc(isNil(r.formulaType) ? '-' : r.formulaType); } }
];

async function loadFunding() {
  const errBox = byId('funding-error');
  showError(errBox, '');
  const instId = String((byId('funding-inst-id') || {}).value || '').trim();
  if (!instId) {
    showError(errBox, '请填写 instId');
    toast('请填写 instId', 'err');
    return;
  }
  const params = { instId: instId, order: (byId('funding-order') || {}).value || 'desc' };
  const limit = readIntField(byId('funding-limit'), 1, 1000, 'limit');
  if (limit === null) return;
  if (limit !== undefined) params.limit = limit;
  const startMs = readTimeMs(byId('funding-start'));
  const endMs = readTimeMs(byId('funding-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    showError(errBox, '开始时间必须早于结束时间');
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) params.startMs = startMs;
  if (endMs !== null) params.endMs = endMs;

  const form = byId('form-funding');
  const btn = form ? form.querySelector('button[type="submit"]') : null;
  const wrap = byId('funding-table-wrap');
  setBusy(btn, true);
  setLoading(wrap, '查询中…');
  try {
    const data = await apiGet('/api/v1/funding-rates', params);
    const list = Array.isArray(data) ? data : [];
    ui.funding.loaded = true;
    renderTable(wrap, FUNDING_COLS, list);
    const summary = byId('funding-summary');
    if (summary) summary.textContent = list.length ? ('共 ' + list.length + ' 条 · ' + instId) : '暂无数据';
    touchRefresh();
  } catch (err) {
    setErrorState(wrap, '查询失败：' + err.message);
    showError(errBox, err.message);
    toast('资金费率查询失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

function initFundingPage() {
  attachInstPicker(byId('funding-inst-id'), { instTypes: ['SWAP', 'FUTURES'] });
  const form = byId('form-funding');
  if (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      loadFunding();
    });
  }
}

/* ======================= 9. 统计查询页 ======================= */

const RATIO_COLS = [
  { title: '时间', render: function (r) { return '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>'; } },
  { title: 'ratioType', key: 'ratioType' },
  { title: 'scopeKind', key: 'scopeKind' },
  { title: 'scope', key: 'scope' },
  { title: 'period', key: 'period' },
  { title: 'longShortRatio', num: true, render: function (r) { return fmtRatio(r.longShortRatio); } }
];

const OIV_COLS = [
  { title: '时间', render: function (r) { return '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>'; } },
  { title: 'ccy', key: 'ccy' },
  { title: 'period', key: 'period' },
  { title: '持仓量 oi', num: true, render: function (r) { return fmtNum(r.oi); } },
  { title: '成交额 vol', num: true, render: function (r) { return fmtNum(r.vol); } }
];

const TAKER_COLS = [
  { title: '时间', render: function (r) { return '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>'; } },
  { title: 'scopeKind', key: 'scopeKind' },
  { title: 'scope', key: 'scope' },
  { title: 'marketType', key: 'marketType' },
  { title: 'period', key: 'period' },
  { title: '卖出量 sellVol', num: true, render: function (r) { return fmtNum(r.sellVol); } },
  { title: '买入量 buyVol', num: true, render: function (r) { return fmtNum(r.buyVol); } }
];

const OI_COLS = [
  { title: '时间', render: function (r) { return '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>'; } },
  { title: 'instId', key: 'instId' },
  { title: 'instType', key: 'instType' },
  { title: '持仓量 oi', num: true, render: function (r) { return fmtNum(r.oi); } },
  { title: 'oiCcy', num: true, render: function (r) { return fmtNum(r.oiCcy); } },
  { title: 'oiUsd', num: true, render: function (r) { return fmtNum(r.oiUsd); } }
];

function activeStatsTab() {
  const tabs = document.querySelectorAll('#stats-tabs .tab');
  for (let i = 0; i < tabs.length; i++) {
    if (tabs[i].classList.contains('is-active')) return tabs[i].getAttribute('data-tab');
  }
  return 'ratios';
}

function switchStatsTab(name) {
  const tabs = document.querySelectorAll('#stats-tabs .tab');
  for (let i = 0; i < tabs.length; i++) {
    const t = tabs[i];
    const on = t.getAttribute('data-tab') === name;
    if (on) t.classList.add('is-active'); else t.classList.remove('is-active');
    t.setAttribute('aria-selected', on ? 'true' : 'false');
  }
  const panels = document.querySelectorAll('.tab-panel');
  for (let i = 0; i < panels.length; i++) {
    const p = panels[i];
    if (p.getAttribute('data-panel') === name) p.classList.add('is-active');
    else p.classList.remove('is-active');
  }
  loadStatsTab(name);
}

function loadStatsTab(name) {
  if (name === 'ratios') loadRatios();
  else if (name === 'oiv') loadOiv();
  else if (name === 'taker') loadTaker();
  else if (name === 'oi') loadOi();
}

function updateRatioHint() {
  const sel = byId('ratio-type');
  const hint = byId('ratio-type-hint');
  if (!sel || !hint) return;
  const code = sel.value;
  let scopeKind = '';
  let name = '';
  for (let i = 0; i < RATIO_TYPES.length; i++) {
    if (RATIO_TYPES[i][0] === code) {
      name = RATIO_TYPES[i][1];
      scopeKind = RATIO_TYPES[i][2];
    }
  }
  if (!code) {
    hint.textContent = '—';
    return;
  }
  const scopeTip = scopeKind === 'CCY' ? 'scope 填币种（如 BTC）' : 'scope 填合约（如 BTC-USDT-SWAP）';
  hint.textContent = name + ' · scopeKind=' + scopeKind + ' · ' + scopeTip;
}

async function loadRatios() {
  const errBox = byId('ratios-error');
  showError(errBox, '');
  const params = {
    ratioType: (byId('ratio-type') || {}).value,
    scope: String((byId('ratio-scope') || {}).value || '').trim(),
    period: (byId('ratio-period') || {}).value,
    order: (byId('ratio-order') || {}).value || 'desc'
  };
  if (!params.ratioType) {
    showError(errBox, '请选择 ratioType');
    toast('请选择 ratioType', 'err');
    return;
  }
  if (!params.scope) {
    showError(errBox, '请填写 scope（CCY 类型填币种，INST 类型填合约 ID）');
    toast('请填写 scope', 'err');
    return;
  }
  const limit = readIntField(byId('ratio-limit'), 1, 1000, 'limit');
  if (limit === null) return;
  if (limit !== undefined) params.limit = limit;
  const startMs = readTimeMs(byId('ratio-start'));
  const endMs = readTimeMs(byId('ratio-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    showError(errBox, '开始时间必须早于结束时间');
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) params.startMs = startMs;
  if (endMs !== null) params.endMs = endMs;

  const form = byId('form-ratios');
  const btn = form ? form.querySelector('button[type="submit"]') : null;
  const wrap = byId('ratios-table-wrap');
  setBusy(btn, true);
  setLoading(wrap, '查询中…');
  try {
    const data = await apiGet('/api/v1/ratios/long-short', params);
    const list = Array.isArray(data) ? data : [];
    ui.stats.loaded.ratios = true;
    renderTable(wrap, RATIO_COLS, list);
    const summary = byId('ratios-summary');
    if (summary) summary.textContent = list.length ? ('共 ' + list.length + ' 条 · ' + params.ratioType + ' / ' + params.scope) : '暂无数据';
    touchRefresh();
  } catch (err) {
    setErrorState(wrap, '查询失败：' + err.message);
    showError(errBox, err.message);
    toast('多空比查询失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

async function loadOiv() {
  const errBox = byId('oiv-error');
  showError(errBox, '');
  const params = {
    ccy: String((byId('oiv-ccy') || {}).value || '').trim(),
    period: (byId('oiv-period') || {}).value,
    order: (byId('oiv-order') || {}).value || 'desc'
  };
  if (!params.ccy) {
    showError(errBox, '请填写 ccy');
    toast('请填写 ccy', 'err');
    return;
  }
  const limit = readIntField(byId('oiv-limit'), 1, 1000, 'limit');
  if (limit === null) return;
  if (limit !== undefined) params.limit = limit;
  const startMs = readTimeMs(byId('oiv-start'));
  const endMs = readTimeMs(byId('oiv-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    showError(errBox, '开始时间必须早于结束时间');
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) params.startMs = startMs;
  if (endMs !== null) params.endMs = endMs;

  const form = byId('form-oiv');
  const btn = form ? form.querySelector('button[type="submit"]') : null;
  const wrap = byId('oiv-table-wrap');
  setBusy(btn, true);
  setLoading(wrap, '查询中…');
  try {
    const data = await apiGet('/api/v1/stats/open-interest-volume', params);
    const list = Array.isArray(data) ? data : [];
    ui.stats.loaded.oiv = true;
    renderTable(wrap, OIV_COLS, list);
    const summary = byId('oiv-summary');
    if (summary) summary.textContent = list.length ? ('共 ' + list.length + ' 条 · ' + params.ccy + ' · ' + params.period) : '暂无数据';
    touchRefresh();
  } catch (err) {
    setErrorState(wrap, '查询失败：' + err.message);
    showError(errBox, err.message);
    toast('持仓量-成交额查询失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

async function loadTaker() {
  const errBox = byId('taker-error');
  showError(errBox, '');
  const params = {
    scopeKind: (byId('taker-scope-kind') || {}).value || 'CCY',
    scope: String((byId('taker-scope') || {}).value || '').trim(),
    period: (byId('taker-period') || {}).value,
    order: (byId('taker-order') || {}).value || 'desc'
  };
  if (!params.scope) {
    showError(errBox, '请填写 scope（CCY 填币种，INST 填合约 ID）');
    toast('请填写 scope', 'err');
    return;
  }
  const limit = readIntField(byId('taker-limit'), 1, 1000, 'limit');
  if (limit === null) return;
  if (limit !== undefined) params.limit = limit;
  const startMs = readTimeMs(byId('taker-start'));
  const endMs = readTimeMs(byId('taker-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    showError(errBox, '开始时间必须早于结束时间');
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) params.startMs = startMs;
  if (endMs !== null) params.endMs = endMs;

  const form = byId('form-taker');
  const btn = form ? form.querySelector('button[type="submit"]') : null;
  const wrap = byId('taker-table-wrap');
  setBusy(btn, true);
  setLoading(wrap, '查询中…');
  try {
    const data = await apiGet('/api/v1/stats/taker-volume', params);
    const list = Array.isArray(data) ? data : [];
    ui.stats.loaded.taker = true;
    renderTable(wrap, TAKER_COLS, list);
    const summary = byId('taker-summary');
    if (summary) summary.textContent = list.length ? ('共 ' + list.length + ' 条 · ' + params.scopeKind + ' / ' + params.scope) : '暂无数据';
    touchRefresh();
  } catch (err) {
    setErrorState(wrap, '查询失败：' + err.message);
    showError(errBox, err.message);
    toast('主动买卖量查询失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

async function loadOi() {
  const errBox = byId('oi-error');
  showError(errBox, '');
  const params = {
    instId: String((byId('oi-inst-id') || {}).value || '').trim(),
    order: (byId('oi-order') || {}).value || 'desc'
  };
  if (!params.instId) {
    showError(errBox, '请填写 instId');
    toast('请填写 instId', 'err');
    return;
  }
  const limit = readIntField(byId('oi-limit'), 1, 1000, 'limit');
  if (limit === null) return;
  if (limit !== undefined) params.limit = limit;
  const startMs = readTimeMs(byId('oi-start'));
  const endMs = readTimeMs(byId('oi-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    showError(errBox, '开始时间必须早于结束时间');
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) params.startMs = startMs;
  if (endMs !== null) params.endMs = endMs;

  const form = byId('form-oi');
  const btn = form ? form.querySelector('button[type="submit"]') : null;
  const wrap = byId('oi-table-wrap');
  setBusy(btn, true);
  setLoading(wrap, '查询中…');
  try {
    const data = await apiGet('/api/v1/stats/open-interest', params);
    const list = Array.isArray(data) ? data : [];
    ui.stats.loaded.oi = true;
    renderTable(wrap, OI_COLS, list);
    const summary = byId('oi-summary');
    if (summary) summary.textContent = list.length ? ('共 ' + list.length + ' 条 · ' + params.instId) : '暂无数据';
    touchRefresh();
  } catch (err) {
    setErrorState(wrap, '查询失败：' + err.message);
    showError(errBox, err.message);
    toast('持仓量快照查询失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

function initStatsPage() {
  const tabs = document.querySelectorAll('#stats-tabs .tab');
  for (let i = 0; i < tabs.length; i++) {
    tabs[i].addEventListener('click', function (e) {
      e.preventDefault();
      switchStatsTab(e.currentTarget.getAttribute('data-tab'));
    });
  }
  fillSelect(byId('ratio-type'), RATIO_TYPES.map(function (x) { return [x[0], x[0] + '（' + x[1] + '）']; }), 'LSR_ACCOUNT_CCY');
  fillSelect(byId('ratio-period'), PERIODS, '1H');
  fillSelect(byId('oiv-period'), PERIODS, '1H');
  fillSelect(byId('taker-period'), PERIODS, '1H');
  attachInstPicker(byId('oi-inst-id'), { instTypes: ['SWAP', 'FUTURES'] });
  const ratioSel = byId('ratio-type');
  if (ratioSel) ratioSel.addEventListener('change', updateRatioHint);
  updateRatioHint();

  const forms = [
    ['form-ratios', loadRatios],
    ['form-oiv', loadOiv],
    ['form-taker', loadTaker],
    ['form-oi', loadOi]
  ];
  for (let i = 0; i < forms.length; i++) {
    const form = byId(forms[i][0]);
    if (!form) continue;
    const fn = forms[i][1];
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      fn();
    });
  }
}

/* ======================= 10. 补拉页 ======================= */

function renderJobCreated(container, data, title) {
  if (!container) return;
  const jobId = data && data.jobId ? data.jobId : '(未知)';
  const taskTotal = data && data.taskTotal !== undefined ? data.taskTotal : '(未知)';
  container.innerHTML = '<div class="result-box is-ok">' +
    '<div><strong>' + esc(title) + '</strong> 提交成功（HTTP 202）</div>' +
    '<div>jobId：<strong>' + esc(jobId) + '</strong></div>' +
    '<div>taskTotal：<strong>' + fmtInt(taskTotal) + '</strong></div>' +
    '<div class="muted">可到 <a href="#/jobs">作业列表</a> 查看进度。</div>' +
    '</div>';
}

function initBackfillBars() {
  const box = byId('bf-kline-bars');
  if (!box) return;
  if (box.children.length) return;
  for (let i = 0; i < BARS.length; i++) {
    const label = document.createElement('label');
    label.className = 'check';
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.value = BARS[i][0];
    if (BARS[i][0] === '1H') input.checked = true;
    const span = document.createElement('span');
    span.textContent = BARS[i][0];
    label.appendChild(input);
    label.appendChild(span);
    box.appendChild(label);
  }
}

function initBackfillPage() {
  fillSelect(byId('bf-kline-inst-type'), [['SWAP', 'SWAP（永续）']], 'SWAP');
  fillSelect(byId('bf-funding-inst-type'), [['SWAP', 'SWAP（永续）'], ['FUTURES', 'FUTURES（交割）']], 'SWAP');
  attachInstPicker(byId('bf-kline-inst-ids'), { multiple: true, instTypes: ['SWAP'] });
  attachInstPicker(byId('bf-funding-inst-ids'), { multiple: true, instTypes: ['SWAP', 'FUTURES'] });
  attachInstPicker(byId('bf-stats-inst-ids'), { multiple: true, instTypes: ['SWAP', 'FUTURES'] });
  initBackfillBars();

  const radios = document.querySelectorAll('.mode-switch input[type="radio"]');
  for (let i = 0; i < radios.length; i++) {
    radios[i].addEventListener('change', function (e) {
      syncModeBlock(e.currentTarget.name);
    });
  }
  syncModeBlock('bf-kline-mode');
  syncModeBlock('bf-funding-mode');

  const bind = function (id, fn) {
    const form = byId(id);
    if (!form) return;
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      fn();
    });
  };
  bind('form-bf-klines', submitKlineBackfill);
  bind('form-bf-funding', submitFundingBackfill);
  bind('form-bf-ratios', submitRatioBackfill);
  bind('form-bf-stats', submitStatsBackfill);
}

function submitButtonOf(form) {
  return form ? form.querySelector('button[type="submit"]') : null;
}

async function submitKlineBackfill() {
  const form = byId('form-bf-klines');
  const result = byId('bf-klines-result');
  const mode = radioValue('bf-kline-mode') || 'type';
  const body = {};

  if (mode === 'ids') {
    const ids = parseList((byId('bf-kline-inst-ids') || {}).value);
    if (!ids.length) {
      toast('按 instIds 模式需要至少填写一个合约 ID', 'err');
      renderErrorBox(result, '请填写 instIds，或切换到「按 instType」模式');
      return;
    }
    body.instIds = ids;
  } else {
    const instType = (byId('bf-kline-inst-type') || {}).value;
    if (!instType) {
      toast('请选择 instType', 'err');
      return;
    }
    body.instType = instType;
    const baseCcy = String((byId('bf-kline-base-ccy') || {}).value || '').trim();
    if (baseCcy) body.baseCcy = baseCcy;
  }

  const bars = checkedValues('bf-bars');
  if (!bars.length) {
    toast('bars 至少选择一个周期', 'err');
    renderErrorBox(result, 'bars 是必填项，请至少勾选一个周期');
    return;
  }
  body.bars = bars;

  const klineTypes = checkedValues('bf-kline-types');
  if (!klineTypes.length) {
    toast('klineTypes 至少选择一个类型', 'err');
    renderErrorBox(result, 'klineTypes 是必填项，请至少勾选一个类型');
    return;
  }
  body.klineTypes = klineTypes;

  const startMs = readTimeMs(byId('bf-kline-start'));
  const endMs = readTimeMs(byId('bf-kline-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) body.startMs = startMs;
  if (endMs !== null) body.endMs = endMs;

  const pageLimit = readIntField(byId('bf-kline-page-limit'), 1, 300, 'pageLimit');
  if (pageLimit === null) return;
  if (pageLimit !== undefined) body.pageLimit = pageLimit;

  const btn = submitButtonOf(form);
  setBusy(btn, true);
  if (result) result.innerHTML = '<div class="result-box"><span class="spinner"></span> 正在提交…</div>';
  try {
    const data = await apiPost('/api/v1/backfill/klines', body);
    renderJobCreated(result, data, 'K 线补拉');
    toast('已创建作业 jobId=' + (data && data.jobId) + '，任务数 ' + (data && data.taskTotal), 'ok');
    touchRefresh();
  } catch (err) {
    renderErrorBox(result, '提交失败：' + err.message);
    toast('K 线补拉提交失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

async function submitFundingBackfill() {
  const form = byId('form-bf-funding');
  const result = byId('bf-funding-result');
  const mode = radioValue('bf-funding-mode') || 'type';
  const body = {};

  if (mode === 'ids') {
    const ids = parseList((byId('bf-funding-inst-ids') || {}).value);
    if (!ids.length) {
      toast('按 instIds 模式需要至少填写一个合约 ID', 'err');
      renderErrorBox(result, '请填写 instIds，或切换到「按 instType」模式');
      return;
    }
    body.instIds = ids;
  } else {
    const instType = (byId('bf-funding-inst-type') || {}).value;
    if (!instType) {
      toast('请选择 instType', 'err');
      return;
    }
    body.instType = instType;
  }

  const startMs = readTimeMs(byId('bf-funding-start'));
  const endMs = readTimeMs(byId('bf-funding-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) body.startMs = startMs;
  if (endMs !== null) body.endMs = endMs;

  const btn = submitButtonOf(form);
  setBusy(btn, true);
  if (result) result.innerHTML = '<div class="result-box"><span class="spinner"></span> 正在提交…</div>';
  try {
    const data = await apiPost('/api/v1/backfill/funding-rates', body);
    renderJobCreated(result, data, '资金费率补拉');
    toast('已创建作业 jobId=' + (data && data.jobId) + '，任务数 ' + (data && data.taskTotal), 'ok');
    touchRefresh();
  } catch (err) {
    renderErrorBox(result, '提交失败：' + err.message);
    toast('资金费率补拉提交失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

async function submitRatioBackfill() {
  const form = byId('form-bf-ratios');
  const result = byId('bf-ratios-result');
  const body = {};

  const ratioTypes = checkedValues('bf-ratio-types');
  if (!ratioTypes.length) {
    toast('ratioTypes 至少选择一个类型', 'err');
    renderErrorBox(result, 'ratioTypes 是必填项');
    return;
  }
  body.ratioTypes = ratioTypes;

  const ccys = parseList((byId('bf-ratio-ccys') || {}).value);
  const scopes = parseList((byId('bf-ratio-scopes') || {}).value);
  const needCcy = ratioTypes.indexOf('LSR_ACCOUNT_CCY') >= 0;
  const needInst = ratioTypes.indexOf('LSR_ACCOUNT_CONTRACT') >= 0 ||
    ratioTypes.indexOf('TOP_TRADER_ACCOUNT') >= 0 ||
    ratioTypes.indexOf('TOP_TRADER_POSITION') >= 0;
  if (needCcy && !ccys.length) {
    toast('所选 ratioTypes 含 CCY 类型，必须填写 ccys', 'err');
    renderErrorBox(result, 'CCY 类型（LSR_ACCOUNT_CCY）必须填写 ccys');
    return;
  }
  if (needInst && !scopes.length) {
    toast('所选 ratioTypes 含 INST 类型，必须填写 scopes', 'err');
    renderErrorBox(result, 'INST 类型（合约维度）必须填写 scopes');
    return;
  }
  if (ccys.length) body.ccys = ccys;
  if (scopes.length) body.scopes = scopes;

  const periods = checkedValues('bf-ratio-periods');
  if (!periods.length) {
    toast('periods 至少选择一个周期', 'err');
    renderErrorBox(result, 'periods 是必填项');
    return;
  }
  body.periods = periods;

  const startMs = readTimeMs(byId('bf-ratio-start'));
  const endMs = readTimeMs(byId('bf-ratio-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) body.startMs = startMs;
  if (endMs !== null) body.endMs = endMs;

  const btn = submitButtonOf(form);
  setBusy(btn, true);
  if (result) result.innerHTML = '<div class="result-box"><span class="spinner"></span> 正在提交…</div>';
  try {
    const data = await apiPost('/api/v1/backfill/ratios', body);
    renderJobCreated(result, data, '多空比补拉');
    toast('已创建作业 jobId=' + (data && data.jobId) + '，任务数 ' + (data && data.taskTotal), 'ok');
    touchRefresh();
  } catch (err) {
    renderErrorBox(result, '提交失败：' + err.message);
    toast('多空比补拉提交失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

async function submitStatsBackfill() {
  const form = byId('form-bf-stats');
  const result = byId('bf-stats-result');
  const body = {};

  const kinds = checkedValues('bf-kinds');
  const effectiveKinds = kinds.length ? kinds : STAT_KINDS.slice();
  if (kinds.length) body.kinds = kinds;

  const ccys = parseList((byId('bf-stats-ccys') || {}).value);
  const instIds = parseList((byId('bf-stats-inst-ids') || {}).value);
  if (effectiveKinds.indexOf('OPEN_INTEREST_VOLUME') >= 0 && !ccys.length) {
    toast('OPEN_INTEREST_VOLUME 需要 ccys', 'err');
    renderErrorBox(result, '所选 kinds 含 OPEN_INTEREST_VOLUME，必须填写 ccys');
    return;
  }
  if (ccys.length) body.ccys = ccys;
  if (instIds.length) body.instIds = instIds;

  const periods = checkedValues('bf-stats-periods');
  if (periods.length) body.periods = periods;

  const startMs = readTimeMs(byId('bf-stats-start'));
  const endMs = readTimeMs(byId('bf-stats-end'));
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    toast('开始时间必须早于结束时间', 'err');
    return;
  }
  if (startMs !== null) body.startMs = startMs;
  if (endMs !== null) body.endMs = endMs;

  const btn = submitButtonOf(form);
  setBusy(btn, true);
  if (result) result.innerHTML = '<div class="result-box"><span class="spinner"></span> 正在提交…</div>';
  try {
    const data = await apiPost('/api/v1/backfill/market-stats', body);
    renderJobCreated(result, data, '行情统计补拉');
    toast('已创建作业 jobId=' + (data && data.jobId) + '，任务数 ' + (data && data.taskTotal), 'ok');
    touchRefresh();
  } catch (err) {
    renderErrorBox(result, '提交失败：' + err.message);
    toast('行情统计补拉提交失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

/* ======================= 11. 作业列表页 ======================= */

function prettyJson(value) {
  if (isNil(value)) return '-';
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      return JSON.stringify(parsed, null, 2);
    } catch (err) {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch (err) {
    return String(value);
  }
}

function renderJobDetail(data) {
  const job = data && data.job ? data.job : (data || {});
  const tasks = data && data.tasks ? data.tasks : [];
  let html = '<div class="table-detail-inner">';
  html += '<div style="padding:10px">';
  html += '<h4>作业信息</h4>';
  html += '<div class="kv-grid">';
  html += '<div class="kv"><span class="kv-key">jobId</span><span class="kv-val">' + esc(job.id) + '</span></div>';
  html += '<div class="kv"><span class="kv-key">状态</span><span class="kv-val">' + badge(job.status) + '</span></div>';
  html += '<div class="kv"><span class="kv-key">任务进度</span><span class="kv-val">' + fmtInt(job.taskDone) + '/' + fmtInt(job.taskTotal) + '（失败 ' + fmtInt(job.taskFailed) + '）</span></div>';
  html += '<div class="kv"><span class="kv-key">写入行数</span><span class="kv-val">' + fmtInt(job.rowsWritten) + '</span></div>';
  html += '<div class="kv"><span class="kv-key">创建时间</span><span class="kv-val">' + esc(fmtTime(parseServerTime(job.createdAt))) + '</span></div>';
  html += '<div class="kv"><span class="kv-key">开始时间</span><span class="kv-val">' + esc(fmtTime(parseServerTime(job.startedAt))) + '</span></div>';
  html += '<div class="kv"><span class="kv-key">结束时间</span><span class="kv-val">' + esc(fmtTime(parseServerTime(job.finishedAt))) + '</span></div>';
  html += '</div>';
  if (job.error) {
    html += '<div class="alert danger" style="margin-top:10px">' + esc(job.error) + '</div>';
  }
  html += '<h4 style="margin-top:12px">request</h4>';
  html += '<pre class="json-out">' + esc(prettyJson(job.request)) + '</pre>';
  html += '</div>';

  html += '<div style="padding:0 10px 10px">';
  html += '<h4>任务列表（' + tasks.length + '）</h4>';
  if (!tasks.length) {
    html += '<div class="state">暂无任务明细</div>';
  } else {
    html += '<div class="table-detail-inner"><table><thead><tr>' +
      '<th>id</th><th>taskKey</th><th>状态</th><th>cursorMs</th><th class="num">pageCount</th>' +
      '<th class="num">rowsWritten</th><th class="num">attempts</th><th>truncated</th><th>lastError</th>' +
      '</tr></thead><tbody>';
    for (let i = 0; i < tasks.length; i++) {
      const t = tasks[i];
      html += '<tr>';
      html += '<td class="mono">' + esc(t.id) + '</td>';
      html += '<td class="mono">' + esc(t.taskKey) + '</td>';
      html += '<td>' + badge(t.status) + '</td>';
      html += '<td class="mono">' + esc(isNil(t.cursorMs) ? '-' : fmtTime(t.cursorMs)) + '</td>';
      html += '<td class="num">' + fmtInt(t.pageCount) + '</td>';
      html += '<td class="num">' + fmtInt(t.rowsWritten) + '</td>';
      html += '<td class="num">' + fmtInt(t.attempts) + '</td>';
      html += '<td>' + esc(isNil(t.truncated) ? '-' : (t.truncated ? '是' : '否')) + '</td>';
      html += '<td>' + (isNil(t.lastError) ? '-' : '<span class="danger-text">' + esc(t.lastError) + '</span>') + '</td>';
      html += '</tr>';
    }
    html += '</tbody></table></div>';
  }
  html += '</div>';
  html += '</div>';
  return html;
}

function jobActionButtons(job) {
  const canPause = job.status === 'PENDING' || job.status === 'RUNNING';
  const canResume = job.status === 'PAUSED' || job.status === 'FAILED';
  const canCancel = job.status !== 'DONE' && job.status !== 'CANCELED';
  const id = esc(job.id);
  let html = '';
  html += '<button class="btn btn-xs" type="button" data-job-action="pause" data-job-id="' + id + '"' + (canPause ? '' : ' disabled') + '>暂停</button>';
  html += '<button class="btn btn-xs" type="button" data-job-action="resume" data-job-id="' + id + '"' + (canResume ? '' : ' disabled') + '>继续</button>';
  html += '<button class="btn btn-xs btn-danger" type="button" data-job-action="cancel" data-job-id="' + id + '"' + (canCancel ? '' : ' disabled') + '>取消</button>';
  return html;
}

function renderJobs(list) {
  const wrap = byId('jobs-table-wrap');
  if (!wrap) return;
  const rows = Array.isArray(list) ? list : [];
  if (!rows.length) {
    setEmpty(wrap, '暂无作业');
    return;
  }
  let html = '<table class="table-jobs"><thead><tr>' +
    '<th>jobId</th><th>dataType</th><th>状态</th><th>进度</th>' +
    '<th class="num">写入行数</th><th>创建时间</th><th>操作</th>' +
    '</tr></thead><tbody>';
  for (let i = 0; i < rows.length; i++) {
    const j = rows[i];
    const total = Number(j.taskTotal) || 0;
    const done = Number(j.taskDone) || 0;
    const pct = total > 0 ? Math.min(100, Math.round(done * 100 / total)) : 0;
    let barCls = '';
    if (j.status === 'DONE') barCls = ' is-done';
    else if (j.status === 'FAILED') barCls = ' is-failed';
    html += '<tr data-job-id="' + esc(j.id) + '">';
    html += '<td class="mono">' + esc(j.id) + '</td>';
    html += '<td>' + esc(DATA_TYPE_LABELS[j.dataType] || j.dataType) + '</td>';
    html += '<td>' + badge(j.status) + '</td>';
    html += '<td><div class="progress-cell"><div class="progress"><div class="bar' + barCls + '" style="width:' + pct + '%"></div></div>' +
      '<span class="progress-text">' + done + '/' + total + '</span></div></td>';
    html += '<td class="num">' + fmtInt(j.rowsWritten) + '</td>';
    html += '<td class="mono">' + esc(fmtTime(parseServerTime(j.createdAt))) + '</td>';
    html += '<td><div class="row-actions"><button class="btn btn-xs" type="button" data-job-toggle="' + esc(j.id) + '">详情</button>' +
      jobActionButtons(j) + '</div></td>';
    html += '</tr>';
    const cached = ui.jobs.expanded[j.id];
    if (cached) {
      html += '<tr class="table-detail" data-job-detail="' + esc(j.id) + '"><td colspan="7">' + cached + '</td></tr>';
    }
  }
  html += '</tbody></table>';
  wrap.innerHTML = html;
}

function insertJobDetailRow(id, html) {
  const row = document.querySelector('tr[data-job-id="' + id + '"]');
  if (!row) return;
  let detail = document.querySelector('tr[data-job-detail="' + id + '"]');
  if (!detail) {
    detail = document.createElement('tr');
    detail.className = 'table-detail';
    detail.setAttribute('data-job-detail', id);
    const td = document.createElement('td');
    td.colSpan = 7;
    detail.appendChild(td);
    row.parentNode.insertBefore(detail, row.nextSibling);
  }
  detail.firstChild.innerHTML = html;
}

function removeJobDetailRow(id) {
  const detail = document.querySelector('tr[data-job-detail="' + id + '"]');
  if (detail && detail.parentNode) detail.parentNode.removeChild(detail);
}

async function toggleJobDetail(id) {
  if (ui.jobs.expanded[id]) {
    delete ui.jobs.expanded[id];
    removeJobDetailRow(id);
    return;
  }
  try {
    const data = await apiGet('/api/v1/backfill/jobs/' + encodeURIComponent(id), { includeTasks: 'true' });
    const html = renderJobDetail(data);
    ui.jobs.expanded[id] = html;
    insertJobDetailRow(id, html);
  } catch (err) {
    toast('加载作业详情失败：' + err.message, 'err');
  }
}

const JOB_ACTION_LABELS = { pause: '暂停', resume: '继续', cancel: '取消' };

async function jobAction(id, action) {
  if (action === 'cancel') {
    const ok = await confirmDialog(
      '取消作业',
      '<p>确认取消作业 <span class="mono">' + esc(id) + '</span>？</p><p class="muted">已写入的数据不会回滚，未执行的任务将被标记为取消。</p>',
      '确认取消'
    );
    if (!ok) return;
  }
  try {
    await apiPost('/api/v1/backfill/jobs/' + encodeURIComponent(id) + '/' + action, null);
    toast('已' + (JOB_ACTION_LABELS[action] || action) + '作业 ' + id, 'ok');
    delete ui.jobs.expanded[id];
    loadJobs(false);
  } catch (err) {
    toast((JOB_ACTION_LABELS[action] || action) + '失败：' + err.message, 'err');
  }
}

function onJobsTableClick(e) {
  const target = e.target;
  if (!target || !target.closest) return;
  const actionBtn = target.closest('[data-job-action]');
  if (actionBtn && !actionBtn.disabled) {
    jobAction(actionBtn.getAttribute('data-job-id'), actionBtn.getAttribute('data-job-action'));
    return;
  }
  const toggleBtn = target.closest('[data-job-toggle]');
  if (toggleBtn) toggleJobDetail(toggleBtn.getAttribute('data-job-toggle'));
}

async function loadJobs(silent) {
  const errBox = byId('jobs-error');
  showError(errBox, '');
  const limitEl = byId('jobs-limit');
  ui.jobs.limit = limitEl && limitEl.value ? Number(limitEl.value) : 50;
  const params = {
    dataType: (byId('jobs-datatype') || {}).value,
    status: (byId('jobs-status') || {}).value,
    limit: ui.jobs.limit,
    offset: ui.jobs.offset
  };
  const wrap = byId('jobs-table-wrap');
  if (!silent) setLoading(wrap, '加载中…');
  try {
    const data = await apiGet('/api/v1/backfill/jobs', params);
    const list = Array.isArray(data) ? data : [];
    ui.jobs.loaded = true;
    renderJobs(list);
    const summary = byId('jobs-summary');
    if (summary) {
      summary.textContent = list.length
        ? ('本页 ' + list.length + ' 条 · 每页 ' + ui.jobs.limit + ' · 无总数字段，翻页按「本页是否满页」判断')
        : '本页无数据';
    }
    const info = byId('jobs-page-info');
    if (info) info.textContent = pagerInfo(ui.jobs.offset, list.length);
    const prev = byId('jobs-prev');
    const next = byId('jobs-next');
    if (prev) prev.disabled = ui.jobs.offset === 0;
    if (next) next.disabled = list.length < ui.jobs.limit;
    touchRefresh();
  } catch (err) {
    if (!silent) setErrorState(wrap, '加载失败：' + err.message);
    showError(errBox, '加载作业列表失败：' + err.message);
    toast('加载作业列表失败：' + err.message, 'err');
  }
}

function initJobsPage() {
  appendSelect(byId('jobs-datatype'), DATA_TYPES.map(function (d) {
    return [d, d + '（' + (DATA_TYPE_LABELS[d] || d) + '）'];
  }));
  appendSelect(byId('jobs-status'), JOB_STATUSES.map(function (s) {
    return [s, s + '（' + (JOB_STATUS_LABELS[s] || s) + '）'];
  }));

  const filterForm = byId('form-jobs-filters');
  if (filterForm) {
    filterForm.addEventListener('submit', function (e) {
      e.preventDefault();
      ui.jobs.offset = 0;
      loadJobs(false);
    });
  }
  const refresh = byId('jobs-refresh');
  if (refresh) refresh.addEventListener('click', function () { loadJobs(false); });

  const prev = byId('jobs-prev');
  if (prev) {
    prev.addEventListener('click', function () {
      ui.jobs.offset = Math.max(0, ui.jobs.offset - ui.jobs.limit);
      loadJobs(false);
    });
  }
  const next = byId('jobs-next');
  if (next) {
    next.addEventListener('click', function () {
      ui.jobs.offset = ui.jobs.offset + ui.jobs.limit;
      loadJobs(false);
    });
  }
  const auto = byId('jobs-auto');
  if (auto) auto.addEventListener('change', startJobsTimer);
  const wrap = byId('jobs-table-wrap');
  if (wrap) wrap.addEventListener('click', onJobsTableClick);
}

/* ======================= 12. 同步页 ======================= */

function syncWaitBox(container, title) {
  if (!container) return function () {};
  const startAt = Date.now();
  container.innerHTML = '<div class="result-box"><span class="spinner"></span> ' + esc(title) +
    '正在执行，已耗时 <strong data-wait>0 秒</strong>（阻塞调用，请勿关闭页面）</div>';
  const span = container.querySelector('[data-wait]');
  const timer = setInterval(function () {
    if (span) span.textContent = Math.round((Date.now() - startAt) / 1000) + ' 秒';
  }, 1000);
  return function () {
    clearInterval(timer);
  };
}

function renderSyncResult(container, data, title, elapsedMs) {
  if (!container) return;
  let head = '<div class="result-box is-ok"><strong>' + esc(title) + '</strong> 完成，耗时 ' + esc(fmtDur(elapsedMs)) + '</div>';
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const parts = [];
    const keys = Object.keys(data);
    for (let i = 0; i < keys.length; i++) {
      const k = keys[i];
      const v = data[k];
      if (typeof v === 'number' || typeof v === 'string' || typeof v === 'boolean') {
        parts.push(esc(k) + '：<strong>' + esc(String(v)) + '</strong>');
      }
    }
    if (parts.length) {
      head = '<div class="result-box is-ok"><strong>' + esc(title) + '</strong> 完成，耗时 ' + esc(fmtDur(elapsedMs)) +
        '<div class="muted" style="margin-top:4px">' + parts.join(' · ') + '</div></div>';
    }
  }
  container.innerHTML = head + '<pre class="json-out">' + esc(prettyJson(data)) + '</pre>';
}

async function submitSyncInstruments() {
  const form = byId('form-sync-instruments');
  const result = byId('sync-instruments-result');
  const instTypes = checkedValues('sync-inst-types');
  const includeOptionsEl = byId('sync-include-options');
  const includeOptions = includeOptionsEl ? includeOptionsEl.checked : false;
  const families = parseList((byId('sync-families') || {}).value);

  const body = { instTypes: instTypes, includeOptions: includeOptions };
  if (families.length) body.families = families;

  const typeText = instTypes.length ? instTypes.join('、') : '（空 → 后端同步全部 5 类）';
  const ok = await confirmDialog(
    '确认全量同步品种',
    '<p>即将调用 <span class="mono">POST /api/v1/sync/instruments</span>。</p><ul>' +
    '<li>instTypes：' + esc(typeText) + '</li>' +
    '<li>includeOptions：' + (includeOptions ? '是（逐家族拉取期权，最慢）' : '否') + '</li>' +
    '<li>families：' + esc(families.length ? families.join('、') : '（自动推断）') + '</li>' +
    '</ul><p class="muted">该接口是同步阻塞调用，可能跑几分钟，期间页面只显示已耗时。请勿刷新页面。</p>',
    '确认开始'
  );
  if (!ok) return;

  const btn = submitButtonOf(form);
  setBusy(btn, true);
  const stopWait = syncWaitBox(result, '品种同步');
  const startAt = Date.now();
  try {
    const data = await apiPost('/api/v1/sync/instruments', body);
    stopWait();
    renderSyncResult(result, data, '品种同步', Date.now() - startAt);
    toast('品种同步完成', 'ok');
    touchRefresh();
  } catch (err) {
    stopWait();
    renderErrorBox(result, '同步失败：' + err.message);
    toast('品种同步失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

async function submitSyncBasic() {
  const form = byId('form-sync-basic');
  const result = byId('sync-basic-result');
  const body = {};

  const instTypes = checkedValues('sync-basic-inst-types');
  if (instTypes.length) body.instTypes = instTypes;
  const incOpt = byId('sync-basic-include-options');
  if (incOpt) body.includeOptions = incOpt.checked;
  const families = parseList((byId('sync-basic-families') || {}).value);
  if (families.length) body.families = families;
  const instType = (byId('sync-basic-inst-type') || {}).value;
  if (instType) body.instType = instType;

  const maxInst = readIntField(byId('sync-basic-max-instruments'), 1, 500, 'maxInstruments');
  if (maxInst === null) return;
  if (maxInst !== undefined) body.maxInstruments = maxInst;
  const maxCcys = readIntField(byId('sync-basic-max-ccys'), 1, 100, 'maxCcys');
  if (maxCcys === null) return;
  if (maxCcys !== undefined) body.maxCcys = maxCcys;
  const days = readIntField(byId('sync-basic-days'), 1, 365, 'days');
  if (days === null) return;
  if (days !== undefined) body.days = days;

  const bars = parseList((byId('sync-basic-bars') || {}).value);
  if (bars.length) body.bars = bars;
  const klineTypes = checkedValues('sync-basic-kline-types');
  if (klineTypes.length) body.klineTypes = klineTypes;
  const incFunding = byId('sync-basic-include-funding');
  if (incFunding) body.includeFunding = incFunding.checked;
  const incRatios = byId('sync-basic-include-ratios');
  if (incRatios) body.includeRatios = incRatios.checked;
  const ratioTypes = checkedValues('sync-basic-ratio-types');
  if (ratioTypes.length) body.ratioTypes = ratioTypes;

  const ok = await confirmDialog(
    '确认组合同步',
    '<p>即将调用 <span class="mono">POST /api/v1/sync/basic</span>（品种同步 + 初始化小规模补拉作业）。</p><ul>' +
    '<li>instType：' + esc(instType || '（默认）') + '</li>' +
    '<li>maxInstruments：' + esc(String(maxInst === undefined ? '默认 20' : maxInst)) + '</li>' +
    '<li>days：' + esc(String(days === undefined ? '默认 7' : days)) + '</li>' +
    '<li>includeFunding / includeRatios：' + (incFunding && incFunding.checked ? '是' : '否') + ' / ' + (incRatios && incRatios.checked ? '是' : '否') + '</li>' +
    '</ul><p class="muted">阻塞调用，可能跑几分钟。</p>',
    '确认开始'
  );
  if (!ok) return;

  const btn = submitButtonOf(form);
  setBusy(btn, true);
  const stopWait = syncWaitBox(result, '组合同步');
  const startAt = Date.now();
  try {
    const data = await apiPost('/api/v1/sync/basic', body);
    stopWait();
    renderSyncResult(result, data, '组合同步', Date.now() - startAt);
    toast('组合同步完成', 'ok');
    touchRefresh();
  } catch (err) {
    stopWait();
    renderErrorBox(result, '同步失败：' + err.message);
    toast('组合同步失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

function initSyncPage() {
  fillSelect(byId('sync-basic-inst-type'), INST_TYPES, 'SWAP');
  const f1 = byId('form-sync-instruments');
  if (f1) {
    f1.addEventListener('submit', function (e) {
      e.preventDefault();
      submitSyncInstruments();
    });
  }
  const f2 = byId('form-sync-basic');
  if (f2) {
    f2.addEventListener('submit', function (e) {
      e.preventDefault();
      submitSyncBasic();
    });
  }
}

/* ======================= 13. 数据浏览页 ======================= */

function joinList(v) {
  if (Array.isArray(v)) return v.length ? v.join('、') : '-';
  if (isNil(v)) return '-';
  return String(v);
}

const COIN_COLS = [
  { title: 'coin', key: 'coin' },
  { title: 'channels', render: function (r) { return esc(joinList(r.channels)); } },
  { title: 'roles', render: function (r) { return esc(joinList(r.roles)); } },
  { title: 'quoteCcys', render: function (r) { return esc(joinList(r.quoteCcys)); } },
  { title: '品种数', num: true, render: function (r) { return fmtInt(r.instCount); } },
  { title: '现货', num: true, render: function (r) { return fmtInt(r.spotInstCount); } },
  { title: '永续', num: true, render: function (r) { return fmtInt(r.swapInstCount); } },
  { title: '交割', num: true, render: function (r) { return fmtInt(r.futuresInstCount); } },
  { title: '期权', num: true, render: function (r) { return fmtInt(r.optionInstCount); } },
  { title: '杠杆', num: true, render: function (r) { return fmtInt(r.marginInstCount); } }
];

const INST_COLS = [
  { title: 'instId', key: 'instId' },
  { title: 'instType', key: 'instType' },
  { title: 'baseCcy', key: 'baseCcy' },
  { title: 'quoteCcy', key: 'quoteCcy' },
  { title: 'state', key: 'state' },
  { title: 'ctVal', num: true, render: function (r) { return fmtNum(r.ctVal); } },
  { title: 'ctMult', num: true, render: function (r) { return fmtNum(r.ctMult); } },
  { title: 'lever', num: true, render: function (r) { return fmtNum(r.lever); } },
  { title: 'tickSz', num: true, render: function (r) { return fmtNum(r.tickSz); } },
  { title: 'lotSz', num: true, render: function (r) { return fmtNum(r.lotSz); } },
  { title: '上市时间', render: function (r) { return '<span class="mono">' + esc(fmtTime(r.listTimeMs)) + '</span>'; } }
];

async function loadCoins() {
  const errBox = byId('coins-error');
  showError(errBox, '');
  const limitEl = byId('coins-limit');
  ui.coins.limit = limitEl && limitEl.value ? Number(limitEl.value) : 50;
  const params = {
    channel: (byId('coins-channel') || {}).value,
    q: String((byId('coins-q') || {}).value || '').trim(),
    limit: ui.coins.limit,
    offset: ui.coins.offset
  };
  const form = byId('form-coins');
  const btn = submitButtonOf(form);
  const wrap = byId('coins-table-wrap');
  setBusy(btn, true);
  setLoading(wrap, '加载中…');
  try {
    const data = await apiGet('/api/v1/coins', params);
    const list = Array.isArray(data) ? data : [];
    ui.coins.loaded = true;
    renderTable(wrap, COIN_COLS, list);
    const summary = byId('coins-summary');
    if (summary) summary.textContent = list.length ? ('本页 ' + list.length + ' 条 · 每页 ' + ui.coins.limit) : '本页无数据';
    const info = byId('coins-page-info');
    if (info) info.textContent = pagerInfo(ui.coins.offset, list.length);
    const prev = byId('coins-prev');
    const next = byId('coins-next');
    if (prev) prev.disabled = ui.coins.offset === 0;
    if (next) next.disabled = list.length < ui.coins.limit;
    touchRefresh();
  } catch (err) {
    setErrorState(wrap, '加载失败：' + err.message);
    showError(errBox, err.message);
    toast('加载币种失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

async function loadInstruments() {
  const errBox = byId('inst-error');
  showError(errBox, '');
  const limitEl = byId('inst-limit');
  ui.inst.limit = limitEl && limitEl.value ? Number(limitEl.value) : 50;
  const params = {
    instType: (byId('inst-type') || {}).value,
    baseCcy: String((byId('inst-base') || {}).value || '').trim(),
    state: (byId('inst-state') || {}).value,
    limit: ui.inst.limit,
    offset: ui.inst.offset
  };
  const form = byId('form-instruments');
  const btn = submitButtonOf(form);
  const wrap = byId('inst-table-wrap');
  setBusy(btn, true);
  setLoading(wrap, '加载中…');
  try {
    const data = await apiGet('/api/v1/instruments', params);
    const list = Array.isArray(data) ? data : [];
    ui.inst.loaded = true;
    renderTable(wrap, INST_COLS, list);
    const summary = byId('inst-summary');
    if (summary) summary.textContent = list.length ? ('本页 ' + list.length + ' 条 · 每页 ' + ui.inst.limit) : '本页无数据';
    const info = byId('inst-page-info');
    if (info) info.textContent = pagerInfo(ui.inst.offset, list.length);
    const prev = byId('inst-prev');
    const next = byId('inst-next');
    if (prev) prev.disabled = ui.inst.offset === 0;
    if (next) next.disabled = list.length < ui.inst.limit;
    touchRefresh();
  } catch (err) {
    setErrorState(wrap, '加载失败：' + err.message);
    showError(errBox, err.message);
    toast('加载品种失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

function initDataPage() {
  const typeSel = byId('inst-type');
  appendSelect(typeSel, INST_TYPES);

  const coinForm = byId('form-coins');
  if (coinForm) {
    coinForm.addEventListener('submit', function (e) {
      e.preventDefault();
      ui.coins.offset = 0;
      loadCoins();
    });
  }
  const coinLimit = byId('coins-limit');
  if (coinLimit) {
    coinLimit.addEventListener('change', function () {
      ui.coins.offset = 0;
      loadCoins();
    });
  }
  const coinPrev = byId('coins-prev');
  if (coinPrev) {
    coinPrev.addEventListener('click', function () {
      ui.coins.offset = Math.max(0, ui.coins.offset - ui.coins.limit);
      loadCoins();
    });
  }
  const coinNext = byId('coins-next');
  if (coinNext) {
    coinNext.addEventListener('click', function () {
      ui.coins.offset = ui.coins.offset + ui.coins.limit;
      loadCoins();
    });
  }

  const instForm = byId('form-instruments');
  if (instForm) {
    instForm.addEventListener('submit', function (e) {
      e.preventDefault();
      ui.inst.offset = 0;
      loadInstruments();
    });
  }
  const instLimit = byId('inst-limit');
  if (instLimit) {
    instLimit.addEventListener('change', function () {
      ui.inst.offset = 0;
      loadInstruments();
    });
  }
  const instPrev = byId('inst-prev');
  if (instPrev) {
    instPrev.addEventListener('click', function () {
      ui.inst.offset = Math.max(0, ui.inst.offset - ui.inst.limit);
      loadInstruments();
    });
  }
  const instNext = byId('inst-next');
  if (instNext) {
    instNext.addEventListener('click', function () {
      ui.inst.offset = ui.inst.offset + ui.inst.limit;
      loadInstruments();
    });
  }
}

/* ======================= 14b. 策略回测页（/api/v1/strategy/backtests） ======================= */

/* 批次附属状态：只有 PENDING/RUNNING 需要轮询 */
const BT_ACTIVE_STATUSES = ['PENDING', 'RUNNING'];

const BT_PHASE_LABELS = { COARSE: '粗筛', REFINE: '精调' };

/* verdict -> [复用徽章配色, 中文] */
const BT_VERDICT_LABELS = {
  CANDIDATE: ['DONE', '候选'],
  REJECT: ['FAILED', '淘汰'],
  INSUFFICIENT: ['PAUSED', '样本不足'],
  COIN: ['CANCELED', '单币']
};

/* 逐币/逐笔拉取条数（后端上限：coins 500 / trades 2000 / equity 5000） */
const BT_COIN_LIMIT = 100;
const BT_TRADE_LIMIT = 200;
const BT_EQUITY_LIMIT = 5000;

/* 后端 JSON 字符串字段（request / params / metrics）容错解析 */
function btParseJson(text) {
  if (isNil(text)) return null;
  if (typeof text === 'object') return text;
  try {
    return JSON.parse(String(text));
  } catch (err) {
    return null;
  }
}

/* 聚合行（ALL）metrics 一层；COIN 行多包了一层 {"metrics":{...}} */
function btMetrics(row) {
  const m = btParseJson(row ? row.metrics : null);
  if (!m || typeof m !== 'object') return null;
  if (m.metrics && typeof m.metrics === 'object') return m.metrics;
  return m;
}

function btStatusActive(status) {
  return BT_ACTIVE_STATUSES.indexOf(String(status || '')) >= 0;
}

function btPhaseLabel(phase) {
  if (isNil(phase)) return '-';
  return BT_PHASE_LABELS[phase] || String(phase);
}

function btVerdictBadge(verdict) {
  const key = String(verdict || '');
  const hit = BT_VERDICT_LABELS[key];
  if (!hit) return '<span class="badge" data-status="UNKNOWN">' + esc(key || '-') + '</span>';
  return '<span class="badge" data-status="' + hit[0] + '">' + esc(hit[1]) + '</span>';
}

/* 百分比单元格：正绿负红 */
function btPctCell(v) {
  if (isNil(v)) return '-';
  const n = Number(v);
  if (!isFinite(n)) return esc(String(v));
  const cls = n > 0 ? 'bt-pos' : (n < 0 ? 'bt-neg' : '');
  const txt = esc(fmtPct(n));
  return cls ? '<span class="' + cls + '">' + txt + '</span>' : txt;
}

/* 浮点近似相等（用于判断后端 inSample 是否与整体指标重复） */
function btSame(a, b) {
  if (isNil(a) && isNil(b)) return true;
  if (isNil(a) || isNil(b)) return false;
  const x = Number(a);
  const y = Number(b);
  if (!isFinite(x) || !isFinite(y)) return String(a) === String(b);
  return Math.abs(x - y) < 1e-9;
}

/* 金额单元格：正绿负红，不做百分比换算（用于 pnl 等 USD 字段） */
function btMoneyCell(v) {
  if (isNil(v)) return '-';
  const n = Number(v);
  if (!isFinite(n)) return esc(String(v));
  const cls = n > 0 ? 'bt-pos' : (n < 0 ? 'bt-neg' : '');
  const txt = esc(fmtMoney(n));
  return cls ? '<span class="' + cls + '">' + txt + '</span>' : txt;
}

function btKv(key, htmlValue) {
  return '<div class="kv"><span class="kv-key">' + esc(key) + '</span><span class="kv-val">' + htmlValue + '</span></div>';
}

/* done/total 进度条（复用 .progress / .bar） */
function btProgressCell(done, total, status) {
  const t = Number(total) || 0;
  const d = Number(done) || 0;
  const pct = t > 0 ? Math.min(100, Math.round(d * 100 / t)) : 0;
  let cls = '';
  if (status === 'DONE') cls = ' is-done';
  else if (status === 'FAILED') cls = ' is-failed';
  return '<div class="progress-cell"><div class="progress"><div class="bar' + cls + '" style="width:' + pct + '%"></div></div>' +
    '<span class="progress-text">' + fmtInt(d) + '/' + fmtInt(t) + '</span></div>';
}

/* 在已加载的结果里按 id 找行（先聚合行，再逐币缓存） */
function btFindRow(resultId) {
  const id = Number(resultId);
  if (!id) return null;
  for (let i = 0; i < ui.bt.results.length; i++) {
    if (Number(ui.bt.results[i].id) === id) return ui.bt.results[i];
  }
  const keys = Object.keys(ui.bt.coins);
  for (let k = 0; k < keys.length; k++) {
    const list = ui.bt.coins[keys[k]] || [];
    for (let i = 0; i < list.length; i++) {
      if (Number(list[i].id) === id) return list[i];
    }
  }
  return null;
}

/* ---------- 提交区 ---------- */

function btFillSample() {
  const ids = byId('bt-inst-ids');
  if (ids) ids.value = 'BTC-USDT-SWAP, ETH-USDT-SWAP';
  const bar = byId('bt-bar');
  if (bar) bar.value = '1H';
  const combos = byId('bt-combos');
  if (combos) combos.value = '40';
  const topn = byId('bt-topn');
  if (topn) topn.value = '3';
  const now = Date.now();
  const start = byId('bt-start');
  const end = byId('bt-end');
  if (end) end.value = toLocalInput(now);
  if (start) start.value = toLocalInput(now - 90 * 86400000);
  toast('已填入小样本参数：2 个币 · bar=1H · combos=40 · topN=3 · 近 90 天', 'info');
}

async function submitBacktest() {
  const errBox = byId('bt-error');
  showError(errBox, '');

  const combos = readIntField(byId('bt-combos'), 1, 2000, 'combos');
  if (combos === null) return;
  const topN = readIntField(byId('bt-topn'), 1, 20, 'topN');
  if (topN === null) return;
  const coinLimit = readIntField(byId('bt-coinlimit'), 1, 50, 'coinLimit');
  if (coinLimit === null) return;
  const maxHold = readIntField(byId('bt-maxhold'), 1, 8760, 'maxHoldHours');
  if (maxHold === null) return;

  const marginRaw = String((byId('bt-margin') || {}).value || '').trim();
  let marginUsd;
  if (marginRaw) {
    const m = Number(marginRaw);
    if (!isFinite(m) || m <= 0) {
      toast('marginUsd 需为正数', 'err');
      return;
    }
    marginUsd = m;
  }

  const instIds = parseList((byId('bt-inst-ids') || {}).value);
  const bar = String((byId('bt-bar') || {}).value || '1m');
  const startMs = readTimeMs(byId('bt-start'));
  const endMs = readTimeMs(byId('bt-end'));
  if (startMs !== null && endMs !== null && endMs - startMs < 7 * 86400000) {
    const msg = '回测窗口最短 7 天，请调整起止时间';
    showError(errBox, msg);
    toast(msg, 'err');
    return;
  }

  const body = { bar: bar };
  if (instIds.length) body.instIds = instIds;
  if (startMs !== null) body.startMs = startMs;
  if (endMs !== null) body.endMs = endMs;
  if (combos !== undefined) body.combos = combos;
  if (topN !== undefined) body.topN = topN;
  if (coinLimit !== undefined) body.coinLimit = coinLimit;
  if (maxHold !== undefined) body.maxHoldHours = maxHold;
  if (marginUsd !== undefined) body.marginUsd = marginUsd;

  const btn = byId('bt-submit');
  setBusy(btn, true);
  try {
    const data = await apiPost('/api/v1/strategy/backtests', body);
    const runId = data && data.runId ? data.runId : 0;
    toast('已创建回测 #' + runId + '，' + fmtInt(data && data.coinTotal) + ' 个币 × ' +
      fmtInt(data && data.combosTotal) + ' 个参数组合', 'ok');
    touchRefresh();
    if (runId) await openBtRun(runId, true);
    await loadBtRuns(true);
  } catch (err) {
    const conflict = err && (err.status === 409 || String(err.code) === 'CONFLICT');
    const msg = conflict
      ? ('已有批次在跑，先取消或等它结束（' + err.message + '）')
      : ('创建回测失败：' + err.message);
    showError(errBox, msg);
    toast(msg, 'err');
  } finally {
    setBusy(btn, false);
  }
}

/* ---------- 批次列表 + 2 秒轮询 ---------- */

function renderBtRuns(rows) {
  const wrap = byId('bt-list-wrap');
  if (!wrap) return;
  const list = Array.isArray(rows) ? rows : [];
  if (!list.length) {
    setEmpty(wrap, '暂无回测批次，先在上方创建');
    return;
  }
  let html = '<div class="table-wrap"><table class="table-jobs"><thead><tr>' +
    '<th>批次</th><th>状态</th><th>bar</th><th>区间</th><th>币进度</th><th>参数进度</th>' +
    '<th>phase</th><th>最好结果</th><th>开始时间</th><th>结束时间</th><th>操作</th>' +
    '</tr></thead><tbody>';
  for (let i = 0; i < list.length; i++) {
    const r = list[i];
    const selected = Number(ui.bt.selectedId) === Number(r.id);
    html += '<tr data-bt-run="' + esc(r.id) + '"' + (selected ? ' class="is-selected bt-run-row"' : ' class="bt-run-row"') + '>';
    html += '<td class="mono">#' + esc(r.id) + '</td>';
    html += '<td>' + badge(r.status) + '</td>';
    html += '<td class="mono">' + esc(r.bar) + '</td>';
    html += '<td class="mono">' + esc(fmtShortTime(r.startMs)) + ' → ' + esc(fmtShortTime(r.endMs)) + '</td>';
    html += '<td>' + btProgressCell(r.coinDone, r.coinTotal, r.status) + '</td>';
    html += '<td>' + btProgressCell(r.combosDone, r.combosTotal, r.status) + '</td>';
    html += '<td>' + esc(btPhaseLabel(r.phase)) + '</td>';
    html += '<td class="mono">' + (isNil(r.bestResultId) ? '-' : '#' + esc(r.bestResultId)) + '</td>';
    html += '<td class="mono">' + esc(fmtTime(parseServerTime(r.startedAt))) + '</td>';
    html += '<td class="mono">' + esc(fmtTime(parseServerTime(r.finishedAt))) + '</td>';
    html += '<td><div class="row-actions"><button class="btn btn-xs" type="button" data-bt-open="' + esc(r.id) + '">查看</button></div></td>';
    html += '</tr>';
  }
  html += '</tbody></table></div>';
  wrap.innerHTML = html;
}

function btHasActiveRun() {
  for (let i = 0; i < ui.bt.runs.length; i++) {
    if (btStatusActive(ui.bt.runs[i].status)) return true;
  }
  return false;
}

/* 只在有 PENDING/RUNNING 且停在回测页时挂 2 秒定时器；stopTimers() 也会兜底清掉 */
function scheduleBtPoll() {
  if (ui.timers.bt) {
    clearInterval(ui.timers.bt);
    ui.timers.bt = 0;
  }
  if (ui.route !== 'strategy-backtest') return;
  if (!btHasActiveRun()) return;
  ui.timers.bt = setInterval(function () {
    if (ui.route !== 'strategy-backtest') return;
    btPollTick();
  }, 2000);
}

async function btPollTick() {
  await loadBtRuns(true);
  const sel = Number(ui.bt.selectedId) || 0;
  if (!sel) return;
  let run = null;
  for (let i = 0; i < ui.bt.runs.length; i++) {
    if (Number(ui.bt.runs[i].id) === sel) run = ui.bt.runs[i];
  }
  if (!run) return;
  const prev = ui.bt.detail && ui.bt.detail.run ? ui.bt.detail.run : null;
  const changed = !prev || prev.status !== run.status || prev.combosDone !== run.combosDone ||
    prev.coinDone !== run.coinDone || prev.bestResultId !== run.bestResultId;
  if (btStatusActive(run.status) || changed) await loadBtDetail(sel, true);
}

async function loadBtRuns(silent) {
  const wrap = byId('bt-list-wrap');
  const box = byId('bt-error');
  if (!silent) {
    setLoading(wrap, '加载批次列表…');
    showError(box, '');
  }
  try {
    const data = await apiGet('/api/v1/strategy/backtests', { limit: 20 });
    const rows = data && Array.isArray(data.items) ? data.items : [];
    ui.bt.runs = rows;
    ui.bt.loaded = true;
    renderBtRuns(rows);
    const summary = byId('bt-list-summary');
    if (summary) {
      const total = data && data.total != null ? fmtInt(data.total) : fmtInt(rows.length);
      summary.textContent = rows.length
        ? ('共 ' + total + ' 个批次 · 显示最近 ' + rows.length + ' 个' + (btHasActiveRun() ? ' · 运行中每 2 秒刷新' : ''))
        : '暂无批次';
    }
  } catch (err) {
    if (!silent) setErrorState(wrap, '加载批次列表失败：' + err.message);
    toast('加载回测批次失败：' + err.message, 'err');
  } finally {
    scheduleBtPoll();
  }
}

/* ---------- 批次详情 ---------- */

async function openBtRun(id, scroll) {
  const runId = Number(id) || 0;
  if (!runId) return;
  if (Number(ui.bt.selectedId) !== runId) {
    ui.bt.expanded = {};
    ui.bt.coins = {};
    ui.bt.champAggId = 0;
    ui.bt.champCoinId = 0;
    ui.bt.equity = [];
    ui.bt.trades = [];
  }
  ui.bt.selectedId = runId;
  renderBtRuns(ui.bt.runs);
  await loadBtDetail(runId, false);
  if (scroll) {
    const card = byId('bt-detail-card');
    if (card && card.scrollIntoView) card.scrollIntoView({ block: 'start' });
  }
}

function renderBtDetail() {
  const box = byId('bt-detail-body');
  if (!box) return;
  const data = ui.bt.detail;
  const run = data && data.run ? data.run : null;
  if (!run) {
    setEmpty(box, '暂无批次数据');
    return;
  }
  const startedMs = parseServerTime(run.startedAt);
  const finishedMs = parseServerTime(run.finishedAt);
  const elapsed = isFinite(startedMs) ? ((isFinite(finishedMs) ? finishedMs : Date.now()) - startedMs) : NaN;

  let html = '<div class="kv-grid">';
  html += btKv('批次', '<span class="mono">#' + esc(run.id) + '</span>');
  html += btKv('状态', badge(run.status));
  html += btKv('bar', '<span class="mono">' + esc(run.bar) + '</span>');
  html += btKv('phase', esc(btPhaseLabel(run.phase)));
  html += btKv('区间', '<span class="mono">' + esc(fmtTime(run.startMs)) + ' → ' + esc(fmtTime(run.endMs)) + '</span>');
  html += btKv('币进度', fmtInt(run.coinDone) + ' / ' + fmtInt(run.coinTotal));
  html += btKv('参数组合进度', fmtInt(run.combosDone) + ' / ' + fmtInt(run.combosTotal));
  html += btKv('冠军结果', isNil(run.bestResultId) ? '-' : '<span class="mono">#' + esc(run.bestResultId) + '</span>');
  html += btKv('结果总数', fmtInt(ui.bt.resultTotal));
  html += btKv('创建时间', esc(fmtTime(parseServerTime(run.createdAt))));
  html += btKv('开始时间', esc(fmtTime(startedMs)));
  html += btKv('结束时间', esc(fmtTime(finishedMs)));
  html += btKv('耗时', isFinite(elapsed) ? esc(fmtDur(elapsed)) : '-');
  if (run.label) html += btKv('备注', esc(run.label));
  html += '</div>';

  html += '<div class="bt-bars">';
  html += '<div><div class="hint">币进度</div>' + btProgressCell(run.coinDone, run.coinTotal, run.status) + '</div>';
  html += '<div><div class="hint">参数组合进度</div>' + btProgressCell(run.combosDone, run.combosTotal, run.status) + '</div>';
  html += '</div>';

  if (run.error) {
    html += '<div class="alert danger" style="margin-top:12px">' + esc(run.error) + '</div>';
  }
  const req = btParseJson(run.request);
  if (req) {
    const ids = Array.isArray(req.instIds) ? req.instIds.join(', ') : '';
    html += '<details class="bt-req"><summary class="hint">request 参数（' + esc(ids || '库内默认币种') +
      '，combos=' + esc(req.combos) + '，topN=' + esc(req.topN) + '）</summary>' +
      '<pre class="json-out">' + esc(prettyJson(req)) + '</pre></details>';
  }
  box.innerHTML = html;
  const cancel = byId('bt-cancel');
  if (cancel) cancel.disabled = !btStatusActive(run.status);
}

async function loadBtDetail(id, silent) {
  const runId = Number(id) || 0;
  const detailCard = byId('bt-detail-card');
  const resultsCard = byId('bt-results-card');
  const champCard = byId('bt-champion-card');
  if (!runId) {
    if (detailCard) detailCard.hidden = true;
    if (resultsCard) resultsCard.hidden = true;
    if (champCard) champCard.hidden = true;
    return;
  }
  if (detailCard) detailCard.hidden = false;
  if (resultsCard) resultsCard.hidden = false;
  if (!silent) setLoading(byId('bt-detail-body'), '加载批次详情…');
  try {
    const data = await apiGet('/api/v1/strategy/backtests/' + runId);
    ui.bt.detail = data && typeof data === 'object' ? data : null;
    ui.bt.results = data && Array.isArray(data.results) ? data.results : [];
    ui.bt.best = data && data.best ? data.best : null;
    ui.bt.resultTotal = data && data.resultTotal != null ? Number(data.resultTotal) : ui.bt.results.length;
    renderBtDetail();
    renderBtResults(ui.bt.results);
    const bestId = ui.bt.best ? Number(ui.bt.best.id)
      : (ui.bt.results.length ? Number(ui.bt.results[0].id) : 0);
    if (bestId && Number(ui.bt.champAggId) !== bestId) {
      ui.bt.champAggId = bestId;
      ui.bt.champCoinId = 0;
    }
    if (ui.bt.champAggId) {
      await btLoadChampion(runId);
    } else if (champCard) {
      champCard.hidden = true;
    }
  } catch (err) {
    setErrorState(byId('bt-detail-body'), '加载失败：' + err.message);
    setErrorState(byId('bt-results-wrap'), '加载失败：' + err.message);
    if (champCard) champCard.hidden = true;
    toast('加载批次详情失败：' + err.message, 'err');
  }
}

/* ---------- 结果排行 + 逐币明细 ---------- */

function renderBtResults(rows) {
  const wrap = byId('bt-results-wrap');
  if (!wrap) return;
  const list = Array.isArray(rows) ? rows : [];
  const summary = byId('bt-result-summary');
  const run = ui.bt.detail && ui.bt.detail.run ? ui.bt.detail.run : null;
  if (summary) {
    summary.textContent = list.length
      ? ('展示前 ' + list.length + ' 个 / 共 ' + fmtInt(ui.bt.resultTotal) + ' 个参数组合 · 点击行展开逐币明细')
      : '';
  }
  if (!list.length) {
    setEmpty(wrap, run && btStatusActive(run.status)
      ? '回测进行中，暂无可排行的参数组合（粗筛完成后出现）'
      : '暂无结果');
    return;
  }
  let html = '<div class="table-wrap"><table class="table-jobs"><thead><tr>' +
    '<th class="num">rank</th><th>paramKey</th><th class="num">净收益率</th><th class="num">最大回撤</th>' +
    '<th class="num">Sharpe</th><th class="num">胜率</th><th class="num">交易数</th>' +
    '<th class="num">样本外收益</th><th>verdict</th><th>操作</th>' +
    '</tr></thead><tbody>';
  for (let i = 0; i < list.length; i++) {
    const r = list[i];
    const selected = Number(ui.bt.champAggId) === Number(r.id);
    html += '<tr data-bt-result="' + esc(r.id) + '"' + (selected ? ' class="is-selected"' : '') + '>';
    html += '<td class="num">' + esc(isNil(r.rank) ? '-' : r.rank) + '</td>';
    html += '<td class="mono">' + esc(isNil(r.paramKey) ? '-' : r.paramKey) + '</td>';
    html += '<td class="num">' + btPctCell(r.netPnlPct) + '</td>';
    html += '<td class="num">' + esc(fmtNum(r.maxDrawdownPct)) + '%</td>';
    html += '<td class="num">' + esc(fmtNum(r.sharpe)) + '</td>';
    html += '<td class="num">' + (isNil(r.winRate) ? '-' : esc(Number(r.winRate).toFixed(2)) + '%') + '</td>';
    html += '<td class="num">' + fmtInt(r.tradeCount) + '</td>';
    html += '<td class="num">' + btPctCell(r.oosNetPnlPct) + '</td>';
    html += '<td>' + btVerdictBadge(r.verdict) + '</td>';
    html += '<td><div class="row-actions"><button class="btn btn-xs" type="button" data-bt-toggle="' +
      esc(r.id) + '">' + (ui.bt.expanded[r.id] ? '收起' : '逐币') + '</button></div></td>';
    html += '</tr>';
    if (ui.bt.expanded[r.id]) {
      html += '<tr class="table-detail" data-bt-coins="' + esc(r.id) + '"><td colspan="10">' +
        renderBtCoinsInner(r.id) + '</td></tr>';
    }
  }
  html += '</tbody></table></div>';
  wrap.innerHTML = html;
}

function renderBtCoinsInner(resultId) {
  const list = ui.bt.coins[resultId];
  if (!list) return '<div class="state"><span class="spinner"></span><span>加载逐币明细…</span></div>';
  if (!list.length) return '<div class="state">该组合暂无逐币明细</div>';
  let html = '<div class="table-detail-inner"><table><thead><tr>' +
    '<th>instId</th><th class="num">交易数</th><th class="num">胜率</th><th class="num">净收益率</th>' +
    '<th class="num">最大回撤</th><th class="num">Sharpe</th><th>verdict</th><th>资金曲线</th>' +
    '</tr></thead><tbody>';
  for (let i = 0; i < list.length; i++) {
    const c = list[i];
    const selected = Number(ui.bt.champCoinId) === Number(c.id);
    html += '<tr' + (selected ? ' class="is-selected"' : '') + '>';
    html += '<td class="mono">' + esc(c.instId) + '</td>';
    html += '<td class="num">' + fmtInt(c.tradeCount) + '</td>';
    html += '<td class="num">' + (isNil(c.winRate) ? '-' : esc(Number(c.winRate).toFixed(2)) + '%') + '</td>';
    html += '<td class="num">' + btPctCell(c.netPnlPct) + '</td>';
    html += '<td class="num">' + esc(fmtNum(c.maxDrawdownPct)) + '%</td>';
    html += '<td class="num">' + esc(fmtNum(c.sharpe)) + '</td>';
    html += '<td>' + btVerdictBadge(c.verdict) + '</td>';
    html += '<td><button class="btn btn-xs" type="button" data-bt-coin="' + esc(c.id) +
      '" data-bt-coin-agg="' + esc(resultId) + '">看曲线</button></td>';
    html += '</tr>';
  }
  html += '</tbody></table></div>';
  return html;
}

async function loadBtCoins(runId, resultId) {
  if (ui.bt.coins[resultId]) return ui.bt.coins[resultId];
  const data = await apiGet('/api/v1/strategy/backtests/' + runId + '/results/' + resultId + '/coins',
    { limit: BT_COIN_LIMIT });
  const list = Array.isArray(data) ? data : [];
  ui.bt.coins[resultId] = list;
  return list;
}

async function toggleBtCoins(resultId) {
  const id = Number(resultId) || 0;
  const runId = Number(ui.bt.selectedId) || 0;
  if (!id || !runId) return;
  if (ui.bt.expanded[id]) {
    delete ui.bt.expanded[id];
    renderBtResults(ui.bt.results);
    return;
  }
  ui.bt.expanded[id] = true;
  renderBtResults(ui.bt.results);
  if (!ui.bt.coins[id]) {
    try {
      await loadBtCoins(runId, id);
    } catch (err) {
      delete ui.bt.expanded[id];
      toast('加载逐币明细失败：' + err.message, 'err');
    }
    renderBtResults(ui.bt.results);
  }
}

/* ---------- 冠军明细 / 资金曲线 / 逐笔成交 ---------- */

async function btLoadChampion(runId) {
  const aggId = Number(ui.bt.champAggId) || 0;
  const card = byId('bt-champion-card');
  if (!card) return;
  if (!aggId) {
    card.hidden = true;
    return;
  }
  card.hidden = false;
  renderBtChampionHead();
  renderBtCoinPicker();
  try {
    await loadBtCoins(runId, aggId);
  } catch (err) {
    toast('加载冠军逐币明细失败：' + err.message, 'err');
  }
  renderBtChampionHead();
  renderBtCoinPicker();
  const viewId = Number(ui.bt.champCoinId) || aggId;
  await Promise.all([loadBtEquity(runId, viewId), loadBtTrades(runId, viewId)]);
}

async function btSetChampion(aggId, coinId) {
  const runId = Number(ui.bt.selectedId) || 0;
  const agg = Number(aggId) || 0;
  if (!runId || !agg) return;
  ui.bt.champAggId = agg;
  const coin = Number(coinId) || 0;
  ui.bt.champCoinId = (coin && coin !== agg) ? coin : 0;
  renderBtResults(ui.bt.results);
  await btLoadChampion(runId);
}

function renderBtChampionHead() {
  const box = byId('bt-champion-body');
  const meta = byId('bt-champion-meta');
  if (!box) return;
  const aggId = Number(ui.bt.champAggId) || 0;
  const agg = aggId ? btFindRow(aggId) : null;
  const coin = ui.bt.champCoinId ? btFindRow(ui.bt.champCoinId) : null;
  const isChampion = !!(ui.bt.best && Number(ui.bt.best.id) === aggId);
  if (meta) {
    let text = '组合 #' + aggId;
    if (agg && !isNil(agg.rank)) text += ' · rank ' + agg.rank;
    if (isChampion) text += ' · 冠军（run.bestResultId）';
    if (coin) text += ' · 当前币 ' + String(coin.instId || '');
    meta.textContent = text;
  }
  if (!agg) {
    box.innerHTML = '<div class="state">组合明细加载中…</div>';
    const promote = byId('bt-promote');
    if (promote) promote.disabled = true;
    return;
  }
  let html = '<div class="kv-grid">';
  html += btKv('paramKey', '<span class="mono">' + esc(agg.paramKey || '-') + '</span>');
  html += btKv('verdict', btVerdictBadge(agg.verdict));
  html += btKv('净收益率', btPctCell(agg.netPnlPct));
  html += btKv('最大回撤', esc(fmtNum(agg.maxDrawdownPct)) + '%');
  html += btKv('Sharpe', esc(fmtNum(agg.sharpe)));
  html += btKv('胜率', isNil(agg.winRate) ? '-' : esc(Number(agg.winRate).toFixed(2)) + '%');
  html += btKv('交易数', fmtInt(agg.tradeCount));
  html += btKv('样本外收益', btPctCell(agg.oosNetPnlPct));
  html += btKv('覆盖币数', fmtInt(agg.coinCount));
  html += btKv('强平次数', fmtInt(agg.liquidationCount));
  html += '</div>';

  const m = btMetrics(agg);
  if (m) {
    const ins = m.inSample || null;
    const oos = m.outOfSample || null;
    /* 聚合行的 metrics.inSample 目前与整体指标同值（后端未区分样本内外），
       与上面一行重复时省略该分组，并给出说明，避免误导。 */
    const showIns = !!(ins && (!btSame(ins.returnPct, agg.netPnlPct) || !btSame(ins.trades, agg.tradeCount)));
    if (showIns) {
      html += '<div class="kv-grid" style="margin-top:10px">';
      html += btKv('样本内收益', btPctCell(ins.returnPct));
      html += btKv('样本内回撤', esc(fmtNum(ins.maxDrawdownPct)) + '%');
      html += btKv('样本内交易', fmtInt(ins.trades));
      html += btKv('样本内 Sharpe', esc(fmtNum(ins.sharpe)));
      html += '</div>';
    }
    if (oos) {
      html += '<div class="kv-grid" style="margin-top:10px">';
      html += btKv('样本外回撤', esc(fmtNum(oos.maxDrawdownPct)) + '%');
      html += btKv('样本外交易', fmtInt(oos.trades));
      if (!isNil(m.score)) html += btKv('score', esc(fmtNum(m.score)));
      html += '</div>';
    }
    if (ins && !showIns) {
      html += '<p class="hint" style="margin-top:8px">接口 metrics.inSample 与整体指标同值（后端未区分样本内外），已省略样本内分组。</p>';
    }
  }

  if (coin) {
    html += '<div class="kv-grid" style="margin-top:10px">';
    html += btKv('当前币', '<span class="mono">' + esc(coin.instId) + '</span>');
    html += btKv('币净收益率', btPctCell(coin.netPnlPct));
    html += btKv('币最大回撤', esc(fmtNum(coin.maxDrawdownPct)) + '%');
    html += btKv('币 Sharpe', esc(fmtNum(coin.sharpe)));
    html += btKv('币胜率', isNil(coin.winRate) ? '-' : esc(Number(coin.winRate).toFixed(2)) + '%');
    html += btKv('币交易数', fmtInt(coin.tradeCount));
    html += '</div>';
  } else {
    html += '<p class="hint" style="margin-top:10px">聚合行（segment=ALL）没有资金曲线数据；' +
      '用下方「查看某个币的资金曲线」选择单个币，或在结果排行里展开「逐币」后点某个币的「看曲线」。</p>';
  }

  const params = btParseJson(agg.params);
  if (params) {
    html += '<details class="bt-req"><summary class="hint">组合参数 params</summary>' +
      '<pre class="json-out">' + esc(prettyJson(params)) + '</pre></details>';
  }
  box.innerHTML = html;
  const promote = byId('bt-promote');
  if (promote) promote.disabled = String(agg.segment || '') !== 'ALL';
}

function renderBtCoinPicker() {
  const box = byId('bt-equity-picker');
  if (!box) return;
  const aggId = Number(ui.bt.champAggId) || 0;
  if (!aggId) {
    box.innerHTML = '';
    return;
  }
  const list = ui.bt.coins[aggId];
  if (!list) {
    box.innerHTML = '<span class="hint">正在加载币种…</span>';
    return;
  }
  if (!list.length) {
    box.innerHTML = '<span class="hint">该组合没有逐币明细</span>';
    return;
  }
  let html = '<span class="hint">查看某个币的资金曲线：</span>';
  for (let i = 0; i < list.length; i++) {
    const c = list[i];
    const on = Number(ui.bt.champCoinId) === Number(c.id);
    html += '<button class="bt-pick' + (on ? ' is-on' : '') + '" type="button" data-bt-pick="' + esc(c.id) +
      '" data-bt-pick-agg="' + esc(aggId) + '">' + esc(c.instId) + ' · ' + esc(fmtPct(c.netPnlPct)) + '</button>';
  }
  const aggOn = !ui.bt.champCoinId;
  html += '<button class="bt-pick' + (aggOn ? ' is-on' : '') + '" type="button" data-bt-pick="' + esc(aggId) +
    '" data-bt-pick-agg="' + esc(aggId) + '">聚合行</button>';
  box.innerHTML = html;
}

async function loadBtEquity(runId, resultId) {
  const meta = byId('bt-equity-meta');
  if (meta) meta.textContent = '加载中…';
  try {
    const data = await apiGet('/api/v1/strategy/backtests/' + runId + '/results/' + resultId + '/equity',
      { limit: BT_EQUITY_LIMIT });
    ui.bt.equity = Array.isArray(data) ? data : [];
  } catch (err) {
    ui.bt.equity = [];
    if (meta) meta.textContent = '加载失败：' + err.message;
  }
  drawBtEquity();
}

/* 资金曲线：canvas 折线，画法与 K 线页 drawChart 一致（devicePixelRatio + 网格 + 折线 + 时间轴） */
function drawBtEquity() {
  const canvas = byId('bt-equity-chart');
  if (!canvas) return;
  const meta = byId('bt-equity-meta');
  const wrap = canvas.parentNode;
  const w = Math.max(320, wrap.clientWidth || 600);
  const h = Math.max(220, wrap.clientHeight || 300);
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  canvas.style.width = w + 'px';
  canvas.style.height = h + 'px';
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);

  const pts = Array.isArray(ui.bt.equity) ? ui.bt.equity : [];
  const isAgg = !ui.bt.champCoinId;
  if (!pts.length) {
    if (meta) {
      meta.textContent = isAgg
        ? '聚合行没有资金曲线数据，请在上方选择一个币'
        : '该币暂未落库资金曲线';
    }
    ctx.fillStyle = COLOR_AXIS;
    ctx.font = '12px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('暂无资金曲线', w / 2, h / 2);
    return;
  }

  const padL = 66;
  const padR = 16;
  const padT = 14;
  const padB = 28;
  const plotW = Math.max(20, w - padL - padR);
  const plotH = Math.max(20, h - padT - padB);

  let min = Infinity;
  let max = -Infinity;
  let maxDD = 0;
  for (let i = 0; i < pts.length; i++) {
    const v = Number(pts[i].equity);
    if (isFinite(v)) {
      if (v < min) min = v;
      if (v > max) max = v;
    }
    const dd = Number(pts[i].drawdown_pct);
    if (isFinite(dd) && dd > maxDD) maxDD = dd;
  }
  if (!isFinite(min) || !isFinite(max)) {
    if (meta) meta.textContent = '资金曲线缺少 equity 字段';
    ctx.fillStyle = COLOR_AXIS;
    ctx.font = '12px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('资金曲线缺少 equity 字段', w / 2, h / 2);
    return;
  }
  const minValue = min;
  const maxValue = max;
  if (max === min) {
    max = min + 1;
    min = min - 1;
  }
  const pad = (max - min) * 0.08;
  min -= pad;
  max += pad;

  const yOf = function (v) {
    return padT + plotH - (v - min) / (max - min) * plotH;
  };
  const xOf = function (i) {
    return padL + (pts.length <= 1 ? plotW / 2 : (i / (pts.length - 1)) * plotW);
  };

  ctx.font = CHART_FONT;
  ctx.lineWidth = 1;
  for (let g = 0; g <= 4; g++) {
    const y = padT + plotH * g / 4;
    ctx.strokeStyle = COLOR_GRID;
    ctx.beginPath();
    ctx.moveTo(padL, y);
    ctx.lineTo(padL + plotW, y);
    ctx.stroke();
    const v = max - (max - min) * g / 4;
    ctx.fillStyle = COLOR_AXIS;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    ctx.fillText(fmtMoney(v), padL - 8, y);
  }

  const base = Number(pts[0].equity);
  if (isFinite(base)) {
    ctx.save();
    ctx.setLineDash([4, 4]);
    ctx.strokeStyle = COLOR_AXIS;
    ctx.beginPath();
    ctx.moveTo(padL, yOf(base));
    ctx.lineTo(padL + plotW, yOf(base));
    ctx.stroke();
    ctx.restore();
  }

  const coords = [];
  for (let i = 0; i < pts.length; i++) {
    const v = Number(pts[i].equity);
    if (!isFinite(v)) continue;
    coords.push([xOf(i), yOf(v)]);
  }
  const last = Number(pts[pts.length - 1].equity);
  const up = !(isFinite(last) && isFinite(base) && last < base);
  const color = up ? COLOR_UP : COLOR_DOWN;

  if (coords.length) {
    ctx.beginPath();
    ctx.moveTo(coords[0][0], coords[0][1]);
    for (let i = 1; i < coords.length; i++) ctx.lineTo(coords[i][0], coords[i][1]);
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.6;
    ctx.stroke();
    ctx.lineTo(coords[coords.length - 1][0], padT + plotH);
    ctx.lineTo(coords[0][0], padT + plotH);
    ctx.closePath();
    ctx.fillStyle = up ? 'rgba(18,164,92,0.10)' : 'rgba(224,83,60,0.10)';
    ctx.fill();
  }

  const firstTs = Number(pts[0].ts_ms);
  const lastTs = Number(pts[pts.length - 1].ts_ms);
  ctx.fillStyle = COLOR_AXIS;
  ctx.textBaseline = 'top';
  ctx.textAlign = 'left';
  ctx.fillText(fmtShortTime(firstTs), padL, padT + plotH + 8);
  ctx.textAlign = 'right';
  ctx.fillText(fmtShortTime(lastTs), padL + plotW, padT + plotH + 8);

  if (meta) {
    meta.textContent = '起点 ' + fmtMoney(base) + ' → 末值 ' + fmtMoney(last) +
      ' · ' + fmtInt(pts.length) + ' 个点 · 峰值 ' + fmtMoney(maxValue) +
      ' · 谷值 ' + fmtMoney(minValue) + ' · 最大回撤 ' + fmtNum(maxDD) + '%';
  }
}

async function loadBtTrades(runId, resultId) {
  const meta = byId('bt-trades-meta');
  if (meta) meta.textContent = '加载中…';
  try {
    const data = await apiGet('/api/v1/strategy/backtests/' + runId + '/trades',
      { resultId: resultId, limit: BT_TRADE_LIMIT });
    ui.bt.trades = Array.isArray(data) ? data : [];
  } catch (err) {
    ui.bt.trades = [];
    if (meta) meta.textContent = '加载失败：' + err.message;
  }
  renderBtTrades();
}

function renderBtTrades() {
  const wrap = byId('bt-trades-wrap');
  if (!wrap) return;
  const list = (ui.bt.trades || []).slice().sort(function (a, b) {
    return (Number(b.exit_ts_ms) || 0) - (Number(a.exit_ts_ms) || 0);
  });
  const meta = byId('bt-trades-meta');
  if (meta) {
    meta.textContent = list.length
      ? ('最近 ' + fmtInt(list.length) + ' 笔（平仓时间倒序）' + (ui.bt.champCoinId ? ' · 仅当前币' : ' · 组合内全部币'))
      : '';
  }
  if (!list.length) {
    setEmpty(wrap, '暂无逐笔成交');
    return;
  }
  let html = '<div class="table-wrap"><table class="table-jobs"><thead><tr>' +
    '<th>instId</th><th>side</th><th class="num">layers</th><th class="num">开仓价</th><th class="num">平仓价</th>' +
    '<th class="num">名义USD</th><th class="num">pnl</th><th class="num">pnl%</th>' +
    '<th class="num">fee</th><th class="num">funding</th>' +
    '<th>开仓时间</th><th>平仓时间</th><th>exit_reason</th>' +
    '</tr></thead><tbody>';
  for (let i = 0; i < list.length; i++) {
    const t = list[i];
    html += '<tr>';
    html += '<td class="mono">' + esc(t.inst_id) + '</td>';
    html += '<td>' + esc(t.side) + '</td>';
    html += '<td class="num">' + fmtInt(t.layers) + '</td>';
    html += '<td class="num">' + esc(fmtNum(t.entry_px)) + '</td>';
    html += '<td class="num">' + esc(fmtNum(t.exit_px)) + '</td>';
    html += '<td class="num">' + esc(fmtMoney(t.notional_usd)) + '</td>';
    html += '<td class="num">' + btMoneyCell(t.pnl) + '</td>';
    html += '<td class="num">' + btPctCell(t.pnl_pct) + '</td>';
    html += '<td class="num">' + esc(fmtNum(t.fee)) + '</td>';
    html += '<td class="num">' + esc(fmtNum(t.funding_paid)) + '</td>';
    html += '<td class="mono">' + esc(fmtTime(Number(t.entry_ts_ms))) + '</td>';
    html += '<td class="mono">' + esc(fmtTime(Number(t.exit_ts_ms))) + '</td>';
    html += '<td>' + esc(t.exit_reason) + '</td>';
    html += '</tr>';
  }
  html += '</tbody></table></div>';
  wrap.innerHTML = html;
}

/* ---------- 取消 / 提升为候选策略 ---------- */

async function cancelBtRun() {
  const runId = Number(ui.bt.selectedId) || 0;
  if (!runId) return;
  const ok = await confirmDialog('取消回测批次',
    '<p>确认取消回测 <span class="mono">#' + esc(runId) + '</span>？</p>' +
    '<p class="muted">已完成的中间结果会保留，未跑完的部分标记为已取消。</p>',
    '确认取消');
  if (!ok) return;
  const btn = byId('bt-cancel');
  setBusy(btn, true);
  try {
    const data = await apiPost('/api/v1/strategy/backtests/' + runId + '/cancel', null);
    toast('已取消回测 #' + runId + '（' + (data && data.status) + '）', 'ok');
    await loadBtRuns(true);
    await loadBtDetail(runId, true);
  } catch (err) {
    toast('取消回测失败：' + err.message, 'err');
  } finally {
    /* setBusy 会无条件恢复 disabled，取消成功后要按最新状态重新禁用按钮 */
    setBusy(btn, false);
    const cur = ui.bt.detail && ui.bt.detail.run;
    if (btn && cur) btn.disabled = !btStatusActive(cur.status);
  }
}

async function promoteBtResult() {
  const runId = Number(ui.bt.selectedId) || 0;
  const aggId = Number(ui.bt.champAggId) || 0;
  if (!runId || !aggId) {
    toast('请先选择批次与组合', 'err');
    return;
  }
  const row = btFindRow(aggId);
  if (!row || String(row.segment || '') !== 'ALL') {
    toast('只有聚合结果（segment=ALL）可以提升为候选策略', 'err');
    return;
  }
  const name = 'bt-' + runId + '-r' + (isNil(row.rank) ? aggId : row.rank);
  const btn = byId('bt-promote');
  setBusy(btn, true);
  try {
    const data = await apiPost('/api/v1/strategy/backtests/' + runId + '/promote', null,
      { resultId: aggId, name: name });
    toast('已提升为候选策略 #' + (data && data.id) + '（' + (data && data.name) + '）', 'ok');
    touchRefresh();
  } catch (err) {
    toast('提升为候选策略失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

/* ---------- 事件绑定（幂等：只在首次进入时挂一次） ---------- */

function onBtClick(e) {
  const target = e.target;
  if (!target || !target.closest) return;
  const open = target.closest('[data-bt-open]');
  if (open) {
    openBtRun(open.getAttribute('data-bt-open'), true);
    return;
  }
  const runRow = target.closest('[data-bt-run]');
  if (runRow) {
    openBtRun(runRow.getAttribute('data-bt-run'), true);
    return;
  }
  const pick = target.closest('[data-bt-pick]');
  if (pick) {
    btSetChampion(pick.getAttribute('data-bt-pick-agg'), pick.getAttribute('data-bt-pick'));
    return;
  }
  const coin = target.closest('[data-bt-coin]');
  if (coin) {
    btSetChampion(coin.getAttribute('data-bt-coin-agg'), coin.getAttribute('data-bt-coin'));
    return;
  }
  const toggle = target.closest('[data-bt-toggle]');
  if (toggle) {
    toggleBtCoins(toggle.getAttribute('data-bt-toggle'));
    return;
  }
  const resultRow = target.closest('[data-bt-result]');
  if (resultRow) {
    toggleBtCoins(resultRow.getAttribute('data-bt-result'));
  }
}

function bindBtEvents() {
  const form = byId('form-backtest');
  if (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      submitBacktest();
    });
  }
  const refresh = byId('bt-refresh');
  if (refresh) refresh.addEventListener('click', function () { loadBtRuns(false); });
  const sample = byId('bt-sample');
  if (sample) sample.addEventListener('click', btFillSample);
  const cancel = byId('bt-cancel');
  if (cancel) cancel.addEventListener('click', cancelBtRun);
  const promote = byId('bt-promote');
  if (promote) promote.addEventListener('click', promoteBtResult);
  const section = document.querySelector('[data-page="strategy-backtest"]');
  if (section) section.addEventListener('click', onBtClick);
}

function initStrategyBacktestPage() {
  const form = byId('form-backtest');
  if (!form) return;
  if (ui.bt.bound) {
    if (ui.route !== 'strategy-backtest') return;
    loadBtRuns(true);
    if (ui.bt.selectedId) loadBtDetail(ui.bt.selectedId, true);
    return;
  }
  ui.bt.bound = true;
  fillSelect(byId('bt-bar'), BARS, '1m');
  attachInstPicker(byId('bt-inst-ids'), { multiple: true, instTypes: ['SWAP'] });
  bindBtEvents();
  if (ui.route !== 'strategy-backtest') return;
  loadBtRuns(false);
}

/* ======================= 14c. 影子交易 / 健康度 / 实盘门禁 ======================= */

/* ---- 三页共用的小工具 ---- */

/* 已有数据时静默刷新：只有首次进入才显示加载态，避免每次轮询把整块闪成「加载中」 */
function pageLoading(node, text, hasData) {
  if (!node || hasData) return;
  setLoading(node, text || '加载中…');
}

/* 体检/门禁 level 徽章：OK / WARN / FAIL / PASS / BLOCK 直接映射 data-status */
function levelBadge(level) {
  const s = isNil(level) ? 'UNKNOWN' : String(level).toUpperCase();
  return '<span class="badge" data-status="' + esc(s) + '">' + esc(s) + '</span>';
}

/* 方向单元格：long/buy 绿、short/sell 红 */
function sideCell(side) {
  const s = isNil(side) ? '' : String(side).toLowerCase();
  const label = isNil(side) ? '-' : String(side);
  if (s === 'long' || s === 'buy') return '<span class="val-pos">' + esc(label) + '</span>';
  if (s === 'short' || s === 'sell') return '<span class="val-neg">' + esc(label) + '</span>';
  return esc(label);
}

/* 正负金额：正绿负红，便于扫盈亏列 */
function signedMoney(v) {
  if (isNil(v)) return '-';
  const n = Number(v);
  if (!isFinite(n)) return esc(String(v));
  const txt = fmtMoney(n);
  if (n > 0) return '<span class="val-pos">+' + esc(txt) + '</span>';
  if (n < 0) return '<span class="val-neg">' + esc(txt) + '</span>';
  return esc(txt);
}

/* 时间字段：毫秒数或服务端 ISO 字符串都能显示成本地时间 */
function timeText(value) {
  if (isNil(value)) return '-';
  const raw = String(value);
  let ms = NaN;
  if (raw.indexOf('-') < 0) {
    const n = Number(raw);
    if (isFinite(n)) ms = n;
  } else {
    ms = parseServerTime(raw);
  }
  return fmtTime(ms);
}

/* metric 是 JSON 字符串：折叠展示；解析不出就原样给出 */
function metricFold(metric) {
  if (isNil(metric)) return '<span class="muted">-</span>';
  return '<details class="metric-fold"><summary class="hint">metric</summary>' +
    '<pre class="json-out">' + esc(prettyJson(metric)) + '</pre></details>';
}

/* 多接口并发时把 Error 挑出来拼成一条提示 */
function collectErrors(values) {
  const parts = [];
  for (let i = 0; i < values.length; i++) {
    if (values[i] instanceof Error && values[i].message) parts.push(values[i].message);
  }
  return parts.join('；');
}

/* ===================== 14c-1. 影子交易（/api/v1/strategy/shadow/*） ===================== */

const SHADOW_POS_LIMIT = 100;
const SHADOW_ORDER_LIMIT = 50;
const SHADOW_PF_LIMIT = 200;
const SHADOW_PF_SHOW = 20;
const SHADOW_POLL_MS = 5000;

function initShadowPage() {
  if (!ui.shadow.bound) {
    ui.shadow.bound = true;
    const refresh = byId('shadow-refresh');
    if (refresh) refresh.addEventListener('click', function () { shadowLoadAll(true); });
    const tick = byId('shadow-tick');
    if (tick) tick.addEventListener('click', shadowTick);
    const auto = byId('shadow-auto');
    if (auto) auto.addEventListener('change', shadowStartTimer);
    const posMore = byId('shadow-pos-more');
    if (posMore) posMore.addEventListener('click', function () { shadowLoadPositions(true); });
    const orderMore = byId('shadow-order-more');
    if (orderMore) orderMore.addEventListener('click', function () { shadowLoadOrders(true); });
    const section = document.querySelector('[data-page="shadow"]');
    if (section) section.addEventListener('click', shadowOnClick);
  }
  if (ui.route !== 'shadow') return;
  shadowLoadAll(false);
  shadowStartTimer();
}

/* 定时刷新：只在本页且勾选了自动刷新时生效；离开路由由 stopTimers 清掉 */
function shadowStartTimer() {
  if (ui.timers.shadow) {
    clearInterval(ui.timers.shadow);
    ui.timers.shadow = 0;
  }
  const auto = byId('shadow-auto');
  if (ui.route !== 'shadow' || !auto || !auto.checked) return;
  ui.timers.shadow = setInterval(function () {
    if (ui.route !== 'shadow') return;
    shadowLoadAll(false);
  }, SHADOW_POLL_MS);
}

/* 平仓按钮走事件委托：表格是整块 innerHTML 重绘的，逐行绑定会丢 */
function shadowOnClick(e) {
  const node = e.target && e.target.closest ? e.target.closest('[data-shadow-close]') : null;
  if (!node) return;
  e.preventDefault();
  const id = Number(node.getAttribute('data-shadow-close'));
  if (!isFinite(id) || id <= 0) return;
  shadowClosePosition(id);
}

async function shadowLoadAll(manual) {
  const errBox = byId('shadow-error');
  showError(errBox, '');
  const btn = byId('shadow-refresh');
  if (manual) setBusy(btn, true);
  const results = await Promise.all([
    shadowLoadStatus().catch(function (e) { return e; }),
    shadowLoadPositions(false).catch(function (e) { return e; }),
    shadowLoadOrders(false).catch(function (e) { return e; }),
    shadowLoadPortfolio().catch(function (e) { return e; })
  ]);
  if (manual) setBusy(btn, false);
  const msg = collectErrors(results);
  if (msg) showError(errBox, '部分数据加载失败：' + msg);
  const updated = byId('shadow-updated');
  if (updated) updated.textContent = '刷新于 ' + fmtTime(Date.now());
  if (manual) toast(msg ? '刷新完成，但有接口失败' : '影子数据已刷新', msg ? 'err' : 'ok');
}

/* ---- 状态 ---- */

async function shadowLoadStatus() {
  const box = byId('shadow-status-body');
  pageLoading(box, '加载中…', !!ui.shadow.status);
  let data;
  try {
    data = await apiGet('/api/v1/strategy/shadow/status');
  } catch (err) {
    if (!ui.shadow.status) setErrorState(box, '引擎状态加载失败：' + err.message);
    throw err;
  }
  ui.shadow.status = data && typeof data === 'object' ? data : {};
  shadowRenderStats(ui.shadow.status);
  shadowRenderStatus(ui.shadow.status);
  shadowRenderLatestHealth(ui.shadow.status.latestHealth);
}

function shadowRenderStats(st) {
  const box = byId('shadow-stats');
  if (!box) return;
  const s = st || {};
  const dur = isNil(s.lastTickDurationMs) ? '-' : fmtInt(s.lastTickDurationMs) + ' ms';
  const cards = [
    { label: '策略数', value: esc(fmtInt(s.strategies)), sub: '参与影子引擎', time: false },
    { label: '影子持仓', value: esc(fmtInt(s.openPositions)), sub: 'OPEN 状态', time: false },
    { label: '最近 tick', value: esc(fmtTime(s.lastTickAtMs)), sub: '耗时 ' + esc(dur), time: true },
    {
      label: '最近体检',
      value: esc(fmtTime(s.lastHealthCheckAtMs)),
      sub: isNil(s.lastHealthSummary) ? '尚未体检' : esc(String(s.lastHealthSummary)),
      time: true
    }
  ];
  let html = '';
  for (let i = 0; i < cards.length; i++) {
    html += '<div class="card stat-card">' +
      '<div class="stat-label">' + esc(cards[i].label) + '</div>' +
      '<div class="stat-value' + (cards[i].time ? ' is-time' : '') + '">' + cards[i].value + '</div>' +
      '<div class="stat-sub">' + cards[i].sub + '</div>' +
      '</div>';
  }
  box.innerHTML = html;
}

function shadowRenderStatus(st) {
  const box = byId('shadow-status-body');
  if (!box) return;
  const s = st || {};
  const kv = [
    ['策略数', fmtInt(s.strategies)],
    ['影子持仓数 openPositions', fmtInt(s.openPositions)],
    ['最近 tick 时间', fmtTime(s.lastTickAtMs)],
    ['最近 tick 耗时', isNil(s.lastTickDurationMs) ? '-' : fmtInt(s.lastTickDurationMs) + ' ms'],
    ['最近体检时间', fmtTime(s.lastHealthCheckAtMs)],
    ['最近体检摘要', isNil(s.lastHealthSummary) ? '-' : String(s.lastHealthSummary)]
  ];
  let html = '<div class="kv-grid">';
  for (let i = 0; i < kv.length; i++) {
    html += '<div class="kv"><span class="kv-key">' + esc(kv[i][0]) + '</span>' +
      '<span class="kv-val">' + esc(kv[i][1]) + '</span></div>';
  }
  html += '</div>';
  html += '<div class="shadow-note"><span class="shadow-note-key">上一轮 note</span>' +
    '<span class="shadow-note-val mono">' + esc(isNil(s.lastTickNote) ? '—' : String(s.lastTickNote)) + '</span></div>';

  const pf = s.latestPortfolio || s.portfolio;
  if (pf && typeof pf === 'object') {
    const rows = [
      ['最新组合 equity', fmtMoney(pf.equityUsd) + ' USD'],
      ['available', fmtMoney(pf.availableUsd) + ' USD'],
      ['持仓数', fmtInt(pf.positionCount)],
      ['gross 名义', fmtMoney(pf.grossNotionalUsd) + ' USD'],
      ['net 名义', fmtMoney(pf.netNotionalUsd) + ' USD'],
      ['未实现盈亏', fmtMoney(pf.unrealizedPnlUsd) + ' USD'],
      ['快照时间', timeText(pf.tsMs || pf.createdAt)]
    ];
    let pfHtml = '<div class="kv-grid shadow-pf-kv">';
    for (let i = 0; i < rows.length; i++) {
      pfHtml += '<div class="kv"><span class="kv-key">' + esc(rows[i][0]) + '</span>' +
        '<span class="kv-val">' + esc(rows[i][1]) + '</span></div>';
    }
    pfHtml += '</div>';
    html += '<div class="shadow-subhead">最新组合快照（已随 status 一并返回）</div>' + pfHtml;
  } else {
    html += '<div class="shadow-subhead">最新组合快照</div>' +
      '<div class="state">暂无数据（status 未返回 latestPortfolio）</div>';
  }
  box.innerHTML = html;
}

/* status.latestHealth 是最近一轮体检明细，直接平铺成表 */
function shadowRenderLatestHealth(rows) {
  const wrap = byId('shadow-hl-wrap');
  const meta = byId('shadow-hl-meta');
  if (!wrap) return;
  const list = Array.isArray(rows) ? rows : [];
  if (meta) {
    meta.textContent = list.length
      ? '取自 status.latestHealth · ' + list.length + ' 条'
      : '取自 status.latestHealth';
  }
  renderTable(wrap, [
    { title: '规则 rule', render: function (r) { return '<span class="mono">' + esc(r.rule) + '</span>'; } },
    { title: 'level', render: function (r) { return levelBadge(r.level); } },
    { title: 'scope', render: function (r) { return '<span class="mono">' + esc(r.scope) + '</span>'; } },
    { title: 'instId', render: function (r) { return '<span class="mono">' + esc(isNil(r.instId) ? '-' : r.instId) + '</span>'; } },
    { title: 'message', render: function (r) { return esc(isNil(r.message) ? '-' : r.message); } },
    { title: 'metric', render: function (r) { return metricFold(r.metric); } }
  ], list);
}

/* ---- 持仓 ---- */

async function shadowLoadPositions(append) {
  const wrap = byId('shadow-pos-wrap');
  const meta = byId('shadow-pos-meta');
  const info = byId('shadow-pos-info');
  const more = byId('shadow-pos-more');
  const offset = append ? (ui.shadow.posRows || []).length : 0;
  pageLoading(wrap, '加载中…', (ui.shadow.posRows || []).length > 0);
  let data;
  try {
    data = await apiGet('/api/v1/strategy/shadow/positions', {
      status: 'OPEN', limit: SHADOW_POS_LIMIT, offset: offset
    });
  } catch (err) {
    if (!append) setErrorState(wrap, '影子持仓加载失败：' + err.message);
    if (meta) meta.textContent = '加载失败';
    throw err;
  }
  const rows = Array.isArray(data) ? data : [];
  ui.shadow.posRows = append ? (ui.shadow.posRows || []).concat(rows) : rows;
  shadowRenderPositions();
  const total = ui.shadow.posRows.length;
  if (info) {
    info.textContent = total
      ? '已加载 ' + total + ' 条 OPEN 持仓（接口无 total 字段，按返回条数判断是否还有更多）'
      : '暂无数据';
  }
  if (meta) meta.textContent = 'GET /positions?status=OPEN · 每页 ' + SHADOW_POS_LIMIT + ' 条';
  if (more) more.hidden = !(rows.length >= SHADOW_POS_LIMIT);
}

function shadowRenderPositions() {
  const wrap = byId('shadow-pos-wrap');
  renderTable(wrap, [
    { title: 'instId', render: function (r) { return '<span class="mono">' + esc(r.instId) + '</span>'; } },
    { title: 'side', render: function (r) { return sideCell(r.side); } },
    { title: 'sz', num: true, render: function (r) { return esc(fmtNum(r.sz)); } },
    { title: '开仓价', num: true, render: function (r) { return esc(fmtNum(r.entryPx)); } },
    { title: '标记价', num: true, render: function (r) { return esc(fmtNum(r.markPx)); } },
    { title: '未实现盈亏 USD', num: true, render: function (r) { return signedMoney(r.unrealizedPnl); } },
    { title: '杠杆', num: true, render: function (r) { return isNil(r.leverage) ? '-' : esc(fmtNum(r.leverage)) + 'x'; } },
    { title: '强平价', num: true, render: function (r) { return esc(fmtNum(r.liqPx)); } },
    { title: '保证金 USD', num: true, render: function (r) { return esc(fmtMoney(r.marginUsd)); } },
    { title: '开仓时间', render: function (r) { return '<span class="mono">' + esc(timeText(r.openedAt)) + '</span>'; } },
    {
      title: '操作',
      render: function (r) {
        return '<button class="btn btn-xs btn-danger" type="button" data-shadow-close="' +
          esc(r.id) + '">平仓</button>';
      }
    }
  ], ui.shadow.posRows || []);
}

/* ---- 订单 ---- */

async function shadowLoadOrders(append) {
  const wrap = byId('shadow-order-wrap');
  const meta = byId('shadow-order-meta');
  const info = byId('shadow-order-info');
  const more = byId('shadow-order-more');
  const offset = append ? (ui.shadow.orderRows || []).length : 0;
  pageLoading(wrap, '加载中…', (ui.shadow.orderRows || []).length > 0);
  let data;
  try {
    data = await apiGet('/api/v1/strategy/shadow/orders', { limit: SHADOW_ORDER_LIMIT, offset: offset });
  } catch (err) {
    if (!append) setErrorState(wrap, '影子订单加载失败：' + err.message);
    if (meta) meta.textContent = '加载失败';
    throw err;
  }
  const rows = Array.isArray(data) ? data : [];
  ui.shadow.orderRows = append ? (ui.shadow.orderRows || []).concat(rows) : rows;
  shadowRenderOrders();
  const total = ui.shadow.orderRows.length;
  if (info) {
    info.textContent = total
      ? '已加载 ' + total + ' 条（接口无 total 字段，按返回条数判断是否还有更多）'
      : '暂无数据';
  }
  if (meta) meta.textContent = 'GET /orders?limit=' + SHADOW_ORDER_LIMIT;
  if (more) more.hidden = !(rows.length >= SHADOW_ORDER_LIMIT);
}

function shadowRenderOrders() {
  const wrap = byId('shadow-order-wrap');
  renderTable(wrap, [
    { title: '时间', render: function (r) { return '<span class="mono">' + esc(timeText(r.decidedAt || r.signalTsMs)) + '</span>'; } },
    { title: 'instId', render: function (r) { return '<span class="mono">' + esc(r.instId) + '</span>'; } },
    { title: 'side', render: function (r) { return sideCell(r.side); } },
    { title: 'reason', render: function (r) { return '<span class="mono">' + esc(isNil(r.reason) ? '-' : r.reason) + '</span>'; } },
    { title: 'status', render: function (r) { return badge(r.status); } },
    { title: 'sz', num: true, render: function (r) { return esc(fmtNum(r.sz)); } },
    { title: 'px', num: true, render: function (r) { return esc(fmtNum(r.px)); } },
    { title: '名义 USD', num: true, render: function (r) { return esc(fmtMoney(r.notionalUsd)); } }
  ], ui.shadow.orderRows || []);
}

/* ---- 组合快照 + 资金曲线 ---- */

async function shadowLoadPortfolio() {
  const wrap = byId('shadow-pf-wrap');
  pageLoading(wrap, '加载中…', (ui.shadow.pfRows || []).length > 0);
  let data;
  try {
    data = await apiGet('/api/v1/strategy/shadow/portfolio', { limit: SHADOW_PF_LIMIT });
  } catch (err) {
    if (!(ui.shadow.pfRows || []).length) setErrorState(wrap, '组合快照加载失败：' + err.message);
    ui.shadow.pfRows = [];
    shadowDrawEquity();
    throw err;
  }
  ui.shadow.pfRows = Array.isArray(data) ? data : [];
  shadowRenderPortfolio();
  shadowDrawEquity();
}

function shadowRenderPortfolio() {
  const wrap = byId('shadow-pf-wrap');
  const meta = byId('shadow-pf-meta');
  const rows = ui.shadow.pfRows || [];
  const latest = rows.length ? rows[0] : null;
  if (meta) {
    meta.textContent = latest
      ? '已加载 ' + rows.length + ' 条 · 最新 equity ' + fmtMoney(latest.equityUsd) +
        ' / available ' + fmtMoney(latest.availableUsd) + ' · 表内仅列最近 ' +
        Math.min(rows.length, SHADOW_PF_SHOW) + ' 条'
      : 'GET /portfolio?limit=' + SHADOW_PF_LIMIT;
  }
  renderTable(wrap, [
    { title: '时间', render: function (r) { return '<span class="mono">' + esc(timeText(r.tsMs)) + '</span>'; } },
    { title: 'equity USD', num: true, render: function (r) { return esc(fmtMoney(r.equityUsd)); } },
    { title: 'available USD', num: true, render: function (r) { return esc(fmtMoney(r.availableUsd)); } },
    { title: '持仓数', num: true, render: function (r) { return esc(fmtInt(r.positionCount)); } },
    { title: 'gross 名义', num: true, render: function (r) { return esc(fmtMoney(r.grossNotionalUsd)); } },
    { title: 'net 名义', num: true, render: function (r) { return esc(fmtMoney(r.netNotionalUsd)); } },
    { title: '未实现盈亏 USD', num: true, render: function (r) { return signedMoney(r.unrealizedPnlUsd); } }
  ], rows.slice(0, SHADOW_PF_SHOW));
}

/* 组合曲线：canvas 折线，画法与 K 线页 drawChart / 回测页 drawBtEquity 一致 */
function shadowDrawEquity() {
  const canvas = byId('shadow-equity-chart');
  if (!canvas) return;
  const wrap = canvas.parentNode;
  const w = Math.max(320, wrap.clientWidth || 600);
  const h = Math.max(180, wrap.clientHeight || 240);
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  canvas.style.width = w + 'px';
  canvas.style.height = h + 'px';
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);

  const all = Array.isArray(ui.shadow.pfRows) ? ui.shadow.pfRows : [];
  const pts = all.slice(0, SHADOW_PF_LIMIT).slice().reverse();
  if (!pts.length) {
    ctx.fillStyle = COLOR_AXIS;
    ctx.font = '12px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('暂无组合快照', w / 2, h / 2);
    return;
  }

  const padL = 74;
  const padR = 16;
  const padT = 14;
  const padB = 26;
  const plotW = Math.max(20, w - padL - padR);
  const plotH = Math.max(20, h - padT - padB);

  let min = Infinity;
  let max = -Infinity;
  for (let i = 0; i < pts.length; i++) {
    const v = Number(pts[i].equityUsd);
    if (isFinite(v)) {
      if (v < min) min = v;
      if (v > max) max = v;
    }
  }
  if (!isFinite(min) || !isFinite(max)) {
    ctx.fillStyle = COLOR_AXIS;
    ctx.font = '12px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('equity 字段不可解析', w / 2, h / 2);
    return;
  }
  if (max - min < 1e-9) {
    const pad = Math.max(0.5, Math.abs(max) * 0.002);
    min -= pad;
    max += pad;
  }
  const yOf = function (v) {
    return padT + plotH - (v - min) / (max - min) * plotH;
  };
  const xOf = function (i) {
    if (pts.length <= 1) return padL + plotW / 2;
    return padL + (i / (pts.length - 1)) * plotW;
  };

  /* 横向网格 + y 轴刻度 */
  ctx.font = CHART_FONT;
  ctx.lineWidth = 1;
  const gridRows = 4;
  for (let i = 0; i <= gridRows; i++) {
    const v = min + (max - min) * (i / gridRows);
    const y = yOf(v);
    ctx.strokeStyle = COLOR_GRID;
    ctx.beginPath();
    ctx.moveTo(padL, y);
    ctx.lineTo(padL + plotW, y);
    ctx.stroke();
    ctx.fillStyle = COLOR_AXIS;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    ctx.fillText(fmtMoney(v), padL - 8, y);
  }

  /* x 轴：首 / 中 / 尾三个时间刻度 */
  const marks = [0, Math.floor((pts.length - 1) / 2), pts.length - 1];
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  for (let i = 0; i < marks.length; i++) {
    const idx = marks[i];
    if (idx < 0) continue;
    const x = xOf(idx);
    ctx.strokeStyle = COLOR_GRID;
    ctx.beginPath();
    ctx.moveTo(x, padT);
    ctx.lineTo(x, padT + plotH);
    ctx.stroke();
    ctx.fillStyle = COLOR_AXIS;
    ctx.fillText(fmtShortTime(Number(pts[idx].tsMs)), x, padT + plotH + 6);
  }

  /* 净值折线 */
  ctx.strokeStyle = '#2f6fed';
  ctx.lineWidth = 1.6;
  ctx.beginPath();
  let started = false;
  for (let i = 0; i < pts.length; i++) {
    const v = Number(pts[i].equityUsd);
    if (!isFinite(v)) continue;
    const x = xOf(i);
    const y = yOf(v);
    if (!started) {
      ctx.moveTo(x, y);
      started = true;
    } else {
      ctx.lineTo(x, y);
    }
  }
  ctx.stroke();

  /* 最新点标记 */
  const lastV = Number(pts[pts.length - 1].equityUsd);
  if (isFinite(lastV)) {
    ctx.fillStyle = '#2f6fed';
    ctx.beginPath();
    ctx.arc(xOf(pts.length - 1), yOf(lastV), 2.6, 0, Math.PI * 2);
    ctx.fill();
  }
}

/* ---- 两个写操作 ---- */

async function shadowTick() {
  const btn = byId('shadow-tick');
  setBusy(btn, true);
  try {
    const res = await apiPost('/api/v1/strategy/shadow/tick');
    const r = res || {};
    toast('已推进一轮：opened=' + fmtInt(r.opened) + ' closed=' + fmtInt(r.closed) +
      ' marked=' + fmtInt(r.marked) + '，耗时 ' + fmtInt(r.durationMs) + ' ms', 'ok');
    await shadowLoadAll(false);
  } catch (err) {
    toast('推进失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

async function shadowClosePosition(id) {
  const title = '确认平掉影子持仓 #' + id + '？';
  const body = '<p>将按最新标记价市价平掉该影子持仓，并在影子订单表里记一笔 MANUAL_CLOSE。' +
    '这是本地影子账本操作，不会向交易所发真实订单。</p>';
  const ok = await confirmDialog(title, body, '确认平仓');
  if (!ok) return;
  try {
    const res = await apiPost('/api/v1/strategy/shadow/positions/' + encodeURIComponent(id) + '/close');
    const r = res || {};
    toast('影子持仓 #' + id + ' 已平仓：exitPx=' + fmtNum(r.exitPx) +
      '，已实现盈亏 ' + fmtMoney(r.unrealizedPnl) + ' USD', 'ok');
    await shadowLoadAll(false);
  } catch (err) {
    if (err.status === 400) toast('平仓失败（' + err.status + '）：' + err.message, 'err');
    else if (err.status === 404) toast('平仓失败（404）：该影子持仓不存在，可能已被平掉', 'err');
    else toast('平仓失败：' + err.message, 'err');
    shadowLoadAll(false);
  }
}

/* ===================== 14c-2. 健康度（/api/v1/strategy/health/*） ===================== */

const HEALTH_PAGE_LIMIT = 100;
const HEALTH_POLL_MS = 15000;
const HEALTH_RULES = [
  'SHADOW_SCHEDULER', 'MARKET_DATA_FRESH', 'UTILIZATION', 'POSITION_EXIT', 'FUNDING_HEADWIND',
  'NO_SHADOW_POSITIONS', 'REAL_TRADING_GATE', 'API_ERROR_RATE', 'BACKFILL_LOAD'
];

function initHealthPage() {
  if (!ui.health.bound) {
    ui.health.bound = true;
    const refresh = byId('health-refresh');
    if (refresh) refresh.addEventListener('click', function () { healthLoadAll(true); });
    const run = byId('health-run');
    if (run) run.addEventListener('click', healthRunCheck);
    const auto = byId('health-auto');
    if (auto) auto.addEventListener('change', healthStartTimer);
    const level = byId('health-level');
    if (level) {
      level.value = ui.health.level;
      level.addEventListener('change', function () {
        ui.health.level = String(level.value || '');
        ui.health.offset = 0;
        healthLoadChecks().catch(function (e) { toast('明细加载失败：' + e.message, 'err'); });
      });
    }
    const prev = byId('health-prev');
    if (prev) {
      prev.addEventListener('click', function () {
        ui.health.offset = Math.max(0, ui.health.offset - ui.health.limit);
        healthLoadChecks().catch(function (e) { toast('明细加载失败：' + e.message, 'err'); });
      });
    }
    const next = byId('health-next');
    if (next) {
      next.addEventListener('click', function () {
        ui.health.offset = ui.health.offset + ui.health.limit;
        healthLoadChecks().catch(function (e) { toast('明细加载失败：' + e.message, 'err'); });
      });
    }
  }
  if (ui.route !== 'health') return;
  healthLoadAll(false);
  healthStartTimer();
}

function healthStartTimer() {
  if (ui.timers.health) {
    clearInterval(ui.timers.health);
    ui.timers.health = 0;
  }
  const auto = byId('health-auto');
  if (ui.route !== 'health' || !auto || !auto.checked) return;
  ui.timers.health = setInterval(function () {
    if (ui.route !== 'health') return;
    healthLoadAll(false);
  }, HEALTH_POLL_MS);
}

async function healthLoadAll(manual) {
  const errBox = byId('health-error');
  showError(errBox, '');
  const btn = byId('health-refresh');
  if (manual) setBusy(btn, true);
  const results = await Promise.all([
    healthLoadLatest().catch(function (e) { return e; }),
    healthLoadChecks().catch(function (e) { return e; })
  ]);
  if (manual) setBusy(btn, false);
  const msg = collectErrors(results);
  if (msg) showError(errBox, '部分数据加载失败：' + msg);
  if (manual) toast(msg ? '刷新完成，但有接口失败' : '健康度已刷新', msg ? 'err' : 'ok');
}

/* /health/latest 返回的是最近一轮的明细数组（不是汇总对象）：计数与前缀都在前端汇总 */
async function healthLoadLatest() {
  const box = byId('health-summary');
  pageLoading(box, '加载中…', !!ui.health.summary);
  let data;
  try {
    data = await apiGet('/api/v1/strategy/health/latest');
  } catch (err) {
    if (!ui.health.summary) setErrorState(box, '最近体检加载失败：' + err.message);
    throw err;
  }
  const rows = Array.isArray(data) ? data : [];
  ui.health.latest = rows;
  healthRenderSummary(rows);
}

function healthRenderSummary(rows) {
  const box = byId('health-summary');
  const meta = byId('health-summary-meta');
  const at = byId('health-checked-at');
  if (!box) return;
  const list = Array.isArray(rows) ? rows : [];
  if (!list.length) {
    ui.health.summary = null;
    setEmpty(box, '暂无体检记录，点右上角「立即体检」跑一轮');
    if (meta) meta.textContent = '暂无体检记录';
    if (at) at.textContent = '最近体检 -';
    return;
  }
  let ok = 0;
  let warn = 0;
  let fail = 0;
  let other = 0;
  let latestMs = 0;
  const ruleSeen = Object.create(null);
  for (let i = 0; i < list.length; i++) {
    const row = list[i];
    const lv = String(isNil(row.level) ? '' : row.level).toUpperCase();
    if (lv === 'OK') ok++;
    else if (lv === 'WARN') warn++;
    else if (lv === 'FAIL') fail++;
    else other++;
    if (!isNil(row.rule)) ruleSeen[String(row.rule)] = true;
    const ms = parseServerTime(row.checkedAt);
    if (isFinite(ms) && ms > latestMs) latestMs = ms;
  }
  const rules = Object.keys(ruleSeen);
  for (let i = 0; i < HEALTH_RULES.length; i++) {
    if (rules.indexOf(HEALTH_RULES[i]) < 0) rules.push(HEALTH_RULES[i]);
  }
  const verdict = fail > 0 ? 'FAIL' : (warn > 0 ? 'WARN' : 'OK');
  ui.health.summary = {
    ok: ok, warn: warn, fail: fail, other: other, total: list.length,
    checkedAtMs: latestMs, verdict: verdict, rules: rules
  };

  let html = '<div class="health-summary-row">';
  html += '<span class="health-pill" data-level="OK">OK <strong>' + fmtInt(ok) + '</strong></span>';
  html += '<span class="health-pill" data-level="WARN">WARN <strong>' + fmtInt(warn) + '</strong></span>';
  html += '<span class="health-pill" data-level="FAIL">FAIL <strong>' + fmtInt(fail) + '</strong></span>';
  if (other) html += '<span class="health-pill" data-level="OTHER">其他 <strong>' + fmtInt(other) + '</strong></span>';
  html += '<span class="health-total">共 ' + fmtInt(list.length) + ' 条检查 · 覆盖 ' + fmtInt(rules.length) + ' 类规则</span>';
  html += '<span class="health-time mono">最近体检 ' + esc(fmtTime(latestMs)) + '</span>';
  html += '</div>';
  html += '<p class="hint health-verdict">整体判定 <strong data-verdict="' + verdict + '">' + verdict +
    '</strong>（有 FAIL 即 FAIL，否则有 WARN 即 WARN）。口径：上一轮 tick 之后的 9 类规则体检结果。</p>';
  box.innerHTML = html;

  if (meta) meta.textContent = '最近一轮 ' + fmtInt(list.length) + ' 条 · ' + fmtTime(latestMs);
  if (at) at.textContent = '最近体检 ' + fmtTime(latestMs);
}

async function healthLoadChecks() {
  const wrap = byId('health-checks-wrap');
  const meta = byId('health-list-meta');
  const info = byId('health-page-info');
  const prev = byId('health-prev');
  const next = byId('health-next');
  pageLoading(wrap, '加载中…', (ui.health.rows || []).length > 0);
  const params = { limit: ui.health.limit, offset: ui.health.offset };
  if (ui.health.level) params.level = ui.health.level;
  let data;
  try {
    data = await apiGet('/api/v1/strategy/health/checks', params);
  } catch (err) {
    setErrorState(wrap, '体检明细加载失败：' + err.message);
    if (meta) meta.textContent = '加载失败';
    if (info) info.textContent = '加载失败';
    throw err;
  }
  ui.health.rows = Array.isArray(data) ? data : [];
  healthRenderChecks();
  const n = ui.health.rows.length;
  if (meta) {
    meta.textContent = (ui.health.level ? 'level=' + ui.health.level + ' · ' : '全部 level · ') +
      '每页 ' + ui.health.limit + ' 条';
  }
  if (info) info.textContent = n ? pagerInfo(ui.health.offset, n) : '暂无数据';
  if (prev) prev.disabled = ui.health.offset <= 0;
  if (next) next.disabled = n < ui.health.limit;
  if (ui.health.offset > 0 && !n) {
    /* 翻过头了：退回到最后一页可用的偏移 */
    ui.health.offset = Math.max(0, ui.health.offset - ui.health.limit);
    if (info) info.textContent = '已到末页';
  }
}

function healthRenderChecks() {
  const wrap = byId('health-checks-wrap');
  renderTable(wrap, [
    { title: '时间', render: function (r) { return '<span class="mono">' + esc(timeText(r.checkedAt)) + '</span>'; } },
    { title: '规则 rule', render: function (r) { return '<span class="mono">' + esc(r.rule) + '</span>'; } },
    { title: 'level', render: function (r) { return levelBadge(r.level); } },
    { title: 'scope', render: function (r) { return '<span class="mono">' + esc(r.scope) + '</span>'; } },
    { title: 'instId', render: function (r) { return '<span class="mono">' + esc(isNil(r.instId) ? '-' : r.instId) + '</span>'; } },
    { title: 'message', render: function (r) { return esc(isNil(r.message) ? '-' : r.message); } },
    { title: 'metric', render: function (r) { return metricFold(r.metric); } }
  ], ui.health.rows || []);
}

async function healthRunCheck() {
  const btn = byId('health-run');
  setBusy(btn, true);
  try {
    const res = await apiPost('/api/v1/strategy/health/check');
    const r = res || {};
    toast('体检完成：' + (r.summary || ('ok=' + fmtInt(r.ok) + ' warn=' + fmtInt(r.warn) +
      ' fail=' + fmtInt(r.fail))) + '，耗时 ' + fmtInt(r.durationMs) + ' ms', 'ok');
    ui.health.offset = 0;
    await healthLoadAll(false);
  } catch (err) {
    toast('体检失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

/* ===================== 14c-3. 实盘门禁（/api/v1/strategy/live/*） ===================== */

const LIVE_POLL_MS = 10000;
const LIVE_CAP_LABELS = {
  maxPositions: '最大持仓数',
  maxDailyNotionalUsd: '当日成交名义上限 USD',
  usedTodayUsd: '当日已用名义 USD',
  capitalPerCoinUsd: '单币分配资金 USD',
  feeTaker: 'taker 手续费率',
  slippageBps: '滑点 bps',
  evaluatedAt: '评估时间'
};
const LIVE_CONFIRM_TOKEN = 'CONFIRM-REAL-TRADING';

function initLivePage() {
  if (!ui.live.bound) {
    ui.live.bound = true;
    attachInstPicker(byId('live-inst'), { instTypes: ['SWAP', 'FUTURES'] });
    const refresh = byId('live-refresh');
    if (refresh) refresh.addEventListener('click', function () { liveLoadAll(true); });
    const auto = byId('live-auto');
    if (auto) auto.addEventListener('change', liveStartTimer);
    const form = byId('form-live-order');
    if (form) form.addEventListener('submit', liveSubmitOrder);
    const ordType = byId('live-ordtype');
    if (ordType) ordType.addEventListener('change', liveSyncPx);
    liveSyncPx();
  }
  if (ui.route !== 'live') return;
  liveLoadAll(false);
  liveStartTimer();
}

function liveStartTimer() {
  if (ui.timers.live) {
    clearInterval(ui.timers.live);
    ui.timers.live = 0;
  }
  const auto = byId('live-auto');
  if (ui.route !== 'live' || !auto || !auto.checked) return;
  ui.timers.live = setInterval(function () {
    if (ui.route !== 'live') return;
    liveLoadAll(false);
  }, LIVE_POLL_MS);
}

/* market 单不要 px，输入框禁用避免误填 */
function liveSyncPx() {
  const ordType = byId('live-ordtype');
  const px = byId('live-px');
  if (!ordType || !px) return;
  const isLimit = String(ordType.value) === 'limit';
  px.disabled = !isLimit;
  if (!isLimit) px.value = '';
}

async function liveLoadAll(manual) {
  const errBox = byId('live-error');
  showError(errBox, '');
  const btn = byId('live-refresh');
  if (manual) setBusy(btn, true);
  const results = await Promise.all([
    liveLoadGate().catch(function (e) { return e; }),
    liveLoadAccount().catch(function (e) { return e; })
  ]);
  if (manual) setBusy(btn, false);
  const msg = collectErrors(results);
  if (msg) showError(errBox, '部分数据加载失败：' + msg);
  const updated = byId('live-updated');
  if (updated) updated.textContent = '刷新于 ' + fmtTime(Date.now());
  if (manual) toast(msg ? '刷新完成，但有接口失败' : '门禁数据已刷新', msg ? 'err' : 'ok');
}

/* ---- 门禁 ---- */

async function liveLoadGate() {
  const box = byId('live-gate-summary');
  pageLoading(box, '加载中…', !!ui.live.gate);
  let data;
  try {
    data = await apiGet('/api/v1/strategy/live/gate');
  } catch (err) {
    if (!ui.live.gate) setErrorState(box, '门禁加载失败：' + err.message);
    throw err;
  }
  ui.live.gate = data && typeof data === 'object' ? data : {};
  liveRenderGate(ui.live.gate);
}

/* blocker 是 "CODE: 说明" 形态，把 CODE 加粗便于扫读 */
function liveBlockerHtml(text) {
  const raw = isNil(text) ? '' : String(text);
  const at = raw.indexOf(': ');
  if (at <= 0 || at > 24) return esc(raw);
  return '<strong>' + esc(raw.slice(0, at)) + '</strong><span>' + esc(raw.slice(at + 2)) + '</span>';
}

function liveRenderGate(gate) {
  const box = byId('live-gate-summary');
  const checksWrap = byId('live-checks-wrap');
  const checksMeta = byId('live-checks-meta');
  const capsBox = byId('live-caps');
  if (!box) return;
  const g = gate || {};
  const mode = isNil(g.mode) ? '-' : String(g.mode);
  const allowed = !!g.liveAllowed;
  const blockers = Array.isArray(g.blockers) ? g.blockers : [];
  const checks = Array.isArray(g.checks) ? g.checks : [];
  const isShadow = mode === 'SHADOW';

  let html = '<div class="live-gate">';
  html += '<div class="live-gate-cell">' +
    '<div class="stat-label">当前模式 mode</div>' +
    '<div class="live-gate-mode">' + esc(mode) + '</div>' +
    '<div class="stat-sub">' + (isShadow ? 'SHADOW 只做本地影子记账，不可能放行实盘' : '实盘模式受门禁约束') + '</div>' +
    '</div>';
  html += '<div class="live-gate-cell">' +
    '<div class="stat-label">放行结果 liveAllowed</div>' +
    '<div class="live-gate-flag ' + (allowed ? 'is-allow' : 'is-block') + '">' +
    (allowed ? 'ALLOW' : 'BLOCK') + '</div>' +
    '<div class="stat-sub">' + (allowed ? '门禁全部通过，可提交真实订单' : '门禁拦截，下单接口返回 HTTP 422') + '</div>' +
    '</div>';
  html += '<div class="live-gate-cell">' +
    '<div class="stat-label">判定</div>' +
    '<div class="live-gate-verdict">' + levelBadge(allowed ? 'PASS' : 'BLOCK') +
    '<span class="live-gate-verdict-text">' + (allowed ? '放行' : '拦截 ' + fmtInt(blockers.length) + ' 项') + '</span></div>' +
    '<div class="stat-sub">' + (isShadow ? 'SHADOW 下 BLOCK 属预期行为' : '') + '</div>' +
    '</div>';
  html += '</div>';

  if (blockers.length) {
    html += '<div class="live-blockers-box">' +
      '<div class="live-blockers-title">拦截原因 blockers（' + fmtInt(blockers.length) + ' 项）</div>' +
      '<ul class="live-blockers">';
    for (let i = 0; i < blockers.length; i++) {
      html += '<li>' + liveBlockerHtml(blockers[i]) + '</li>';
    }
    html += '</ul></div>';
  } else if (allowed) {
    html += '<div class="live-gate-ok">没有拦截项：模式、开关、密钥、风控上限都已通过。</div>';
  }
  if (!allowed && isShadow) {
    html += '<p class="hint live-gate-hint">提示：把环境切到 DEMO/REAL 并打开实盘开关、配置 API 密钥后，' +
      '这里才可能变成 ALLOW；在 SHADOW 下它永远是 BLOCK，下单接口的 422 是预期结果，不是故障。</p>';
  }
  box.innerHTML = html;

  /* 检查明细表 */
  if (checksWrap) {
    renderTable(checksWrap, [
      { title: '检查项 id', render: function (r) { return '<span class="mono">' + esc(r.id) + '</span>'; } },
      { title: 'level', render: function (r) { return levelBadge(r.level); } },
      { title: 'message', render: function (r) { return esc(isNil(r.message) ? '-' : r.message); } },
      { title: 'metric', render: function (r) { return metricFold(r.metric); } }
    ], checks);
  }
  if (checksMeta) {
    let pass = 0;
    let warnN = 0;
    let block = 0;
    for (let i = 0; i < checks.length; i++) {
      const lv = String(isNil(checks[i].level) ? '' : checks[i].level).toUpperCase();
      if (lv === 'PASS') pass++;
      else if (lv === 'WARN') warnN++;
      else if (lv === 'BLOCK') block++;
    }
    checksMeta.textContent = 'PASS ' + fmtInt(pass) + ' · WARN ' + fmtInt(warnN) + ' · BLOCK ' + fmtInt(block);
  }

  /* 风控上限 caps */
  if (capsBox) {
    const caps = g.caps && typeof g.caps === 'object' ? g.caps : null;
    if (!caps) {
      setEmpty(capsBox, '暂无数据（gate 未返回 caps）');
    } else {
      const keys = Object.keys(caps);
      const ordered = [];
      for (let i = 0; i < Object.keys(LIVE_CAP_LABELS).length; i++) {
        const k = Object.keys(LIVE_CAP_LABELS)[i];
        if (keys.indexOf(k) >= 0) ordered.push(k);
      }
      for (let i = 0; i < keys.length; i++) {
        if (ordered.indexOf(keys[i]) < 0) ordered.push(keys[i]);
      }
      let capHtml = '<div class="kv-grid live-caps-grid">';
      for (let i = 0; i < ordered.length; i++) {
        const k = ordered[i];
        const v = caps[k];
        let text;
        if (k === 'evaluatedAt') text = timeText(v);
        else if (k === 'feeTaker') text = fmtRatio(v);
        else if (typeof v === 'number' && /Usd|Bps/.test(k) === false) text = fmtInt(v);
        else text = fmtNum(v);
        capHtml += '<div class="kv"><span class="kv-key">' + esc(LIVE_CAP_LABELS[k] || k) + '</span>' +
          '<span class="kv-val">' + esc(text) + '</span></div>';
      }
      capHtml += '</div>';
      capsBox.innerHTML = capHtml;
    }
  }
}

/* ---- 账户 ---- */

async function liveLoadAccount() {
  const box = byId('live-account');
  pageLoading(box, '加载中…', !!ui.live.account);
  let data;
  try {
    data = await apiGet('/api/v1/strategy/live/account');
  } catch (err) {
    if (!ui.live.account) setErrorState(box, '账户加载失败：' + err.message);
    throw err;
  }
  ui.live.account = data && typeof data === 'object' ? data : {};
  liveRenderAccount(ui.live.account);
}

/* 配置密钥后余额/持仓的字段由交易所决定，这里按返回对象的键动态出列，最多 10 列 */
function liveDynamicCols(list) {
  const cols = [];
  if (!list || !list.length) return cols;
  const keys = Object.keys(list[0] || {});
  for (let i = 0; i < keys.length && cols.length < 10; i++) {
    cols.push((function (key) {
      return {
        title: key,
        render: function (row) {
          const v = row[key];
          if (isNil(v)) return '-';
          if (typeof v === 'object') return '<span class="mono">' + esc(JSON.stringify(v)) + '</span>';
          return esc(String(v));
        }
      };
    })(keys[i]));
  }
  return cols;
}

function liveRenderAccount(acc) {
  const box = byId('live-account');
  if (!box) return;
  const a = acc || {};
  const balance = Array.isArray(a.balance) ? a.balance : [];
  const positions = Array.isArray(a.positions) ? a.positions : [];
  const mode = isNil(a.mode) ? '-' : String(a.mode);
  let html = '';
  if (a.configured) {
    html += '<div class="live-account-on">已配置 API 密钥（mode=' + esc(mode) +
      '）：余额 ' + fmtInt(balance.length) + ' 条 · 持仓 ' + fmtInt(positions.length) + ' 条，数据来自交易所。</div>';
  } else {
    html += '<div class="live-account-off">' +
      '<div class="live-account-off-title">未配置 API 密钥（configured=false）</div>' +
      '<div class="live-account-off-reason">' + esc(isNil(a.reason) ? '当前只有本地影子账本可用。' : a.reason) + '</div>' +
      '<div class="live-account-off-meta mono">mode=' + esc(mode) + ' · balance=' + fmtInt(balance.length) +
      ' 条 · positions=' + fmtInt(positions.length) + ' 条</div>' +
      '</div>';
  }
  if (balance.length) {
    html += '<div class="card-head live-account-head"><h2>余额 balance</h2>' +
      '<span class="muted">' + fmtInt(balance.length) + ' 条</span></div>' +
      '<div class="table-wrap" id="live-balance-wrap"></div>';
  } else {
    html += '<div class="card-head live-account-head"><h2>余额 balance</h2>' +
      '<span class="muted">0 条</span></div><div class="state">暂无数据</div>';
  }
  if (positions.length) {
    html += '<div class="card-head live-account-head"><h2>持仓 positions</h2>' +
      '<span class="muted">' + fmtInt(positions.length) + ' 条</span></div>' +
      '<div class="table-wrap" id="live-positions-wrap"></div>';
  } else {
    html += '<div class="card-head live-account-head"><h2>持仓 positions</h2>' +
      '<span class="muted">0 条</span></div><div class="state">暂无数据</div>';
  }
  box.innerHTML = html;
  if (balance.length) renderTable(byId('live-balance-wrap'), liveDynamicCols(balance), balance);
  if (positions.length) renderTable(byId('live-positions-wrap'), liveDynamicCols(positions), positions);
}

/* ---- 受控下单 ---- */

async function liveSubmitOrder(e) {
  if (e && e.preventDefault) e.preventDefault();
  const instEl = byId('live-inst');
  const sideEl = byId('live-side');
  const szEl = byId('live-sz');
  const ordEl = byId('live-ordtype');
  const pxEl = byId('live-px');
  const tdEl = byId('live-tdmode');
  const reduceEl = byId('live-reduce');
  const confirmEl = byId('live-confirm');

  const instId = String(instEl && instEl.value ? instEl.value : '').trim();
  const side = String(sideEl && sideEl.value ? sideEl.value : 'buy');
  const szRaw = String(szEl && szEl.value ? szEl.value : '').trim();
  const ordType = String(ordEl && ordEl.value ? ordEl.value : 'market');
  const pxRaw = String(pxEl && pxEl.value ? pxEl.value : '').trim();
  const tdMode = String(tdEl && tdEl.value ? tdEl.value : 'isolated');
  const reduceOnly = !!(reduceEl && reduceEl.checked);
  const confirm = String(confirmEl && confirmEl.value ? confirmEl.value : '').trim();

  if (!instId) {
    toast('请先填 instId（可聚焦输入框从下拉里选）', 'err');
    if (instEl) instEl.focus();
    return;
  }
  const sz = Number(szRaw);
  if (!isFinite(sz) || sz <= 0) {
    toast('sz 需为大于 0 的合约张数', 'err');
    if (szEl) szEl.focus();
    return;
  }
  let px = null;
  if (ordType === 'limit') {
    px = Number(pxRaw);
    if (!isFinite(px) || px <= 0) {
      toast('限价单必须填大于 0 的 px', 'err');
      if (pxEl) pxEl.focus();
      return;
    }
  }

  const gate = ui.live.gate || {};
  const mode = isNil(gate.mode) ? '-' : String(gate.mode);
  const allowed = !!gate.liveAllowed;

  let bodyHtml = '<ul class="live-confirm-list">' +
    '<li>instId：<code>' + esc(instId) + '</code></li>' +
    '<li>side / ordType：<code>' + esc(side) + '</code> / <code>' + esc(ordType) + '</code></li>' +
    '<li>sz：<code>' + esc(szRaw) + '</code> 张' + (ordType === 'limit'
      ? '，px：<code>' + esc(pxRaw) + '</code>' : '（市价）') + '</li>' +
    '<li>tdMode：<code>' + esc(tdMode) + '</code> · reduceOnly：' + (reduceOnly ? '是' : '否') + '</li>' +
    '<li>confirm：<code>' + esc(confirm || '(空)') + '</code></li>' +
    '</ul>';
  if (allowed) {
    bodyHtml += '<p class="hint">门禁当前 liveAllowed=true，这次提交会真的发往交易所。</p>';
  } else {
    bodyHtml += '<p class="hint">当前 mode=<strong>' + esc(mode) + '</strong>、liveAllowed=false：' +
      '后端会以 HTTP 422 拒绝并列出拦截原因，这是预期行为，不会真的下单。</p>';
  }
  const ok = await confirmDialog('确认提交下单请求？', bodyHtml, '确认提交');
  if (!ok) return;

  const body = {
    instId: instId,
    side: side,
    sz: sz,
    ordType: ordType,
    tdMode: tdMode,
    reduceOnly: reduceOnly
  };
  if (ordType === 'limit') body.px = px;
  if (confirm) body.confirm = confirm;

  const btn = byId('live-submit');
  setBusy(btn, true);
  try {
    const res = await apiPost('/api/v1/strategy/live/orders', body);
    ui.live.lastOrder = res;
    liveRenderOrderResult(res, null);
    toast('下单请求已被接受', 'ok');
    liveLoadGate().catch(function () { /* 单次刷新失败不打断 */ });
  } catch (err) {
    ui.live.lastOrder = null;
    liveRenderOrderResult(null, err);
    if (err && err.status === 422) toast('被门禁拦截（符合预期）：HTTP 422', 'info');
    else toast('下单失败：' + err.message, 'err');
  } finally {
    setBusy(btn, false);
  }
}

/* 422 不是故障：按后端 msg 拆出 blockers，展示成「被门禁拦截（符合预期）」 */
function liveRenderOrderResult(res, err) {
  const box = byId('live-order-result');
  if (!box) return;
  if (err && err.status === 422) {
    const msg = String(err.message || '');
    const prefix = 'live trading blocked -> ';
    const detail = msg.indexOf(prefix) === 0 ? msg.slice(prefix.length) : msg;
    const parts = detail.split(' | ');
    let html = '<div class="live-block-note">';
    html += '<div class="live-block-note-title">被门禁拦截（符合预期）</div>';
    html += '<p class="live-block-note-sub">HTTP 422 · 请求在门禁处被拒绝，没有发往交易所。' +
      '当前 SHADOW 环境门禁必然 BLOCK，这是设计行为，不是接口故障。</p>';
    html += '<div class="live-http mono">HTTP 422 · code=' + esc(err.code || 'INVALID_PARAM') + '</div>';
    html += '<ul class="live-blockers">';
    for (let i = 0; i < parts.length; i++) {
      if (!String(parts[i]).trim()) continue;
      html += '<li>' + liveBlockerHtml(parts[i]) + '</li>';
    }
    html += '</ul>';
    html += '<details class="metric-fold"><summary class="hint">完整响应 msg</summary>' +
      '<pre class="json-out">' + esc(msg) + '</pre></details>';
    html += '</div>';
    box.innerHTML = html;
    return;
  }
  if (err) {
    box.innerHTML = '<div class="result-box is-err">' + esc('下单失败（HTTP ' +
      (err.status || 0) + '）：' + (err.message || '未知错误')) + '</div>';
    return;
  }
  const r = res || {};
  let html = '<div class="result-box is-ok">' +
    '<strong>accepted=' + esc(isNil(r.accepted) ? '-' : r.accepted) + '</strong>' +
    ' · ordId ' + esc(isNil(r.ordId) ? '(空)' : r.ordId) +
    ' · clOrdId ' + esc(isNil(r.clOrdId) ? '-' : r.clOrdId) +
    '</div>';
  html += '<details class="metric-fold"><summary class="hint">完整响应</summary>' +
    '<pre class="json-out">' + esc(prettyJson(r)) + '</pre></details>';
  box.innerHTML = html;
}

/* ===================== 16. 账户 / 我的订单 / 马丁策略（只读） ===================== */

const ACC_NA = '\u2014';

/* 空值统一显示为 ——；金额 2 位、数量最多 6 位、百分比 2 位 */
const nfAccMoney = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const nfAccQty = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 6 });
const nfAccPx = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 8 });

function accMoney(v) {
  if (isNil(v)) return ACC_NA;
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  return nfAccMoney.format(n);
}

function accQty(v) {
  if (isNil(v)) return ACC_NA;
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  return nfAccQty.format(n);
}

function accPx(v) {
  if (isNil(v)) return ACC_NA;
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  return nfAccPx.format(n);
}

function accNum(v) {
  if (isNil(v)) return ACC_NA;
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  return nfNum.format(n);
}

function accInt(v) {
  if (isNil(v)) return ACC_NA;
  return fmtInt(v);
}

/* 布尔值（含后端返回的字符串 "true"/"false"）-> 是 / 否；空值 — */
function accBool(v) {
  if (isNil(v) || v === '') return ACC_NA;
  if (v === true || v === 'true') return '是';
  if (v === false || v === 'false') return '否';
  return String(v);
}

/* 比率 0.0158 -> 1.58%；signed=true 时正数带 + */
function accPct(v, signed) {
  if (isNil(v)) return ACC_NA;
  const n = Number(v);
  if (!isFinite(n)) return String(v);
  const p = n * 100;
  return (signed && p > 0 ? '+' : '') + p.toFixed(2) + '%';
}

/* 毫秒时间戳或服务端 ISO 字符串 -> 本地时间；空值 — */
function accTime(v) {
  if (isNil(v)) return ACC_NA;
  const t = timeText(v);
  return isNil(t) || t === '-' ? ACC_NA : t;
}

/* 正负金额：正绿负红；空值 — */
function accPnl(v) {
  if (isNil(v)) return ACC_NA;
  const n = Number(v);
  if (!isFinite(n)) return esc(String(v));
  const txt = nfAccMoney.format(Math.abs(n));
  if (n > 0) return '<span class="val-pos">+' + esc(txt) + '</span>';
  if (n < 0) return '<span class="val-neg">-' + esc(txt) + '</span>';
  return esc(txt);
}

/* 接口 error 对象/字符串 -> 一句话；无错误返回空串 */
function accIssueText(data) {
  const d = data && typeof data === 'object' ? data : {};
  const err = d.error;
  if (isNil(err)) return d.ok === false ? '接口返回 ok=false，未给出 error 详情' : '';
  if (typeof err === 'string') return err;
  const parts = [];
  if (!isNil(err.code)) parts.push('[' + String(err.code) + ']');
  if (!isNil(err.message)) parts.push(String(err.message));
  if (!isNil(err.hint)) parts.push('提示：' + String(err.hint));
  return parts.length ? parts.join(' ') : '接口返回 ok=false';
}

function accAlertHtml(text) {
  return '<div class="alert danger">' + esc(text) + '</div>';
}

/* key-value 网格；pairs = [{key, value} | {key, html}] */
function accKvGrid(pairs) {
  const list = Array.isArray(pairs) ? pairs : [];
  let html = '<div class="kv-grid">';
  for (let i = 0; i < list.length; i++) {
    const p = list[i];
    if (!p) continue;
    let val;
    if (p.html !== undefined) val = p.html;
    else {
      let v = p.value;
      if (typeof v === 'boolean') v = v ? '是' : '否';
      val = esc(isNil(v) ? ACC_NA : v);
    }
    html += '<div class="kv"><span class="kv-key">' + esc(p.key) + '</span><span class="kv-val">' + val + '</span></div>';
  }
  return html + '</div>';
}

/* 和 renderTable 同样的列定义，但返回 HTML 字符串（详情卡里内联拼装用） */
function accTableHtml(cols, rows, emptyText) {
  const list = Array.isArray(rows) ? rows : [];
  if (!list.length) return '<div class="state">' + esc(emptyText || '暂无数据') + '</div>';
  let html = '<table><thead><tr>';
  for (let i = 0; i < cols.length; i++) {
    html += '<th' + (cols[i].num ? ' class="num"' : '') + '>' + esc(cols[i].title) + '</th>';
  }
  html += '</tr></thead><tbody>';
  for (let r = 0; r < list.length; r++) {
    const row = list[r] || {};
    html += '<tr>';
    for (let c = 0; c < cols.length; c++) {
      const col = cols[c];
      let cell;
      if (typeof col.render === 'function') cell = col.render(row);
      else cell = esc(isNil(row[col.key]) ? ACC_NA : row[col.key]);
      html += '<td' + (col.num ? ' class="num"' : '') + '>' + cell + '</td>';
    }
    html += '</tr>';
  }
  return html + '</tbody></table>';
}

/* ===================== 16a. 账户概览（/api/v1/account/*） ===================== */

const ACC_BALANCE_COLS = [
  { title: 'ccy', render: function (r) { return '<span class="mono">' + esc(isNil(r.ccy) ? ACC_NA : r.ccy) + '</span>'; } },
  { title: 'eq', num: true, render: function (r) { return esc(accQty(r.eq)); } },
  { title: 'eqUsd', num: true, render: function (r) { return esc(accMoney(r.eqUsd)); } },
  { title: 'cashBal', num: true, render: function (r) { return esc(accQty(r.cashBal)); } },
  { title: 'availBal', num: true, render: function (r) { return esc(accQty(r.availBal)); } },
  { title: 'availEq', num: true, render: function (r) { return esc(accQty(r.availEq)); } },
  { title: 'frozenBal', num: true, render: function (r) { return esc(accQty(r.frozenBal)); } },
  { title: 'ordFrozen', num: true, render: function (r) { return esc(accQty(r.ordFrozen)); } },
  { title: 'upl', num: true, render: function (r) { return accPnl(r.upl); } },
  { title: 'uplLiab', num: true, render: function (r) { return esc(accQty(r.uplLiab)); } },
  { title: 'disEq', num: true, render: function (r) { return esc(accMoney(r.disEq)); } }
];

const ACC_POSITION_COLS = [
  { title: 'instId', render: function (r) { return '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>'; } },
  { title: 'posSide', render: function (r) { return sideCell(r.posSide); } },
  { title: 'pos 张', num: true, render: function (r) { return esc(accQty(r.pos)); } },
  { title: 'avgPx', num: true, render: function (r) { return esc(accPx(r.avgPx)); } },
  { title: 'markPx', num: true, render: function (r) { return esc(accPx(r.markPx)); } },
  { title: 'upl', num: true, render: function (r) { return accPnl(r.upl); } },
  { title: 'uplRatio', num: true, render: function (r) { return esc(accPct(r.uplRatio, true)); } },
  { title: 'lever', num: true, render: function (r) { return esc(accNum(r.lever)); } },
  { title: 'liqPx', num: true, render: function (r) { return esc(accPx(r.liqPx)); } },
  { title: 'margin', num: true, render: function (r) { return esc(accMoney(r.margin)); } },
  { title: 'notionalUsd', num: true, render: function (r) { return esc(accMoney(r.notionalUsd)); } },
  { title: 'mgnMode', render: function (r) { return esc(isNil(r.mgnMode) ? ACC_NA : r.mgnMode); } },
  { title: 'adl', num: true, render: function (r) { return esc(isNil(r.adl) ? ACC_NA : r.adl); } },
  { title: 'uTime', render: function (r) { return '<span class="mono">' + esc(accTime(r.uTime)) + '</span>'; } }
];

function initAccountPage() {
  if (!ui.account.bound) {
    ui.account.bound = true;
    const refresh = byId('account-refresh');
    if (refresh) refresh.addEventListener('click', function () { accountLoadAll(true); });
  }
  if (ui.route !== 'account') return;
  accountLoadAll(false);
}

async function accountLoadAll(manual) {
  const errBox = byId('account-error');
  showError(errBox, '');
  const btn = byId('account-refresh');
  if (manual) setBusy(btn, true);
  const results = await Promise.all([
    accountLoadSummary().catch(function (e) { return e; }),
    accountLoadBalance().catch(function (e) { return e; }),
    accountLoadPositions().catch(function (e) { return e; })
  ]);
  if (manual) setBusy(btn, false);
  const msg = collectErrors(results);
  if (msg) showError(errBox, '部分数据加载失败：' + msg);
  const updated = byId('account-updated');
  if (updated) updated.textContent = '刷新于 ' + fmtTime(Date.now());
  if (manual) toast(msg ? '刷新完成，但有接口失败' : '账户数据已刷新', msg ? 'err' : 'ok');
}

async function accountLoadSummary() {
  const box = byId('account-summary');
  pageLoading(box, '加载中…', !!ui.account.summary);
  let data;
  try {
    data = await apiGet('/api/v1/account/summary');
  } catch (err) {
    if (!ui.account.summary) setErrorState(box, '凭证信息加载失败：' + err.message);
    throw err;
  }
  ui.account.summary = data && typeof data === 'object' ? data : {};
  accRenderSummary(ui.account.summary);
}

function accRenderSummary(d) {
  const box = byId('account-summary');
  if (!box) return;
  const data = d || {};
  const issue = accIssueText(data);
  if (issue) {
    box.innerHTML = accAlertHtml(issue);
    return;
  }
  const cred = data.credentials && typeof data.credentials === 'object' ? data.credentials : {};
  const acc = data.account && typeof data.account === 'object' ? data.account : {};
  let html = '<p class="hint">模式 ' + esc(isNil(data.mode) ? ACC_NA : data.mode) +
    ' · 凭证已配置 ' + esc(data.configured ? '是' : '否') +
    ' · 抓取时间 ' + esc(accTime(data.fetchedAt)) + '</p>';
  // 未配置凭证时后端只回 notice 不回 account，必须显式提示，否则页面全是「—」看不出原因。
  if (!isNil(data.notice)) {
    html += accAlertHtml(data.notice);
  }
  html += accKvGrid([
    { key: '凭证标签 label', value: cred.label },
    { key: 'apiKey（脱敏）', html: '<span class="mono">' + esc(isNil(cred.apiKey) ? ACC_NA : cred.apiKey) + '</span>' },
    { key: 'apiKey 已配置', value: cred.apiKeyConfigured },
    { key: 'secretKey 已配置', value: cred.secretKeyConfigured },
    { key: 'passphrase 已配置', value: cred.passphraseConfigured },
    { key: '凭证来源 source', value: cred.source },
    { key: '权限 perm', value: acc.perm },
    { key: '账户模式 acctLv', value: acc.acctLv },
    { key: '持仓模式 posMode', value: acc.posMode },
    { key: '自成交保护 acctStpMode', value: acc.acctStpMode },
    { key: '自动借币 autoLoan', html: '<span>' + esc(accBool(acc.autoLoan)) + '</span>' },
    { key: 'uid（脱敏）', html: '<span class="mono">' + esc(isNil(acc.uid) ? ACC_NA : acc.uid) + '</span>' },
    { key: '主账户 mainUid（脱敏）', html: '<span class="mono">' + esc(isNil(acc.mainUid) ? ACC_NA : acc.mainUid) + '</span>' },
    { key: 'KYC 等级 kycLv', value: acc.kycLv },
    { key: '等级 level', value: acc.level }
  ]);
  if (!isNil(data.permissionNote)) {
    html += '<p class="hint">' + esc(data.permissionNote) + '</p>';
  }
  box.innerHTML = html;
}

async function accountLoadBalance() {
  const box = byId('account-balance');
  const wrap = byId('account-balance-table');
  pageLoading(box, '加载中…', !!ui.account.balance);
  let data;
  try {
    data = await apiGet('/api/v1/account/balance');
  } catch (err) {
    if (!ui.account.balance) {
      setErrorState(box, '余额加载失败：' + err.message);
      setEmpty(wrap, '余额明细不可用');
    }
    throw err;
  }
  ui.account.balance = data && typeof data === 'object' ? data : {};
  accRenderBalance(ui.account.balance);
}

function accRenderBalance(d) {
  const box = byId('account-balance');
  const wrap = byId('account-balance-table');
  if (!box) return;
  const data = d || {};
  const issue = accIssueText(data);
  if (issue) {
    box.innerHTML = accAlertHtml(issue);
    if (wrap) setEmpty(wrap, '余额明细不可用');
    return;
  }
  const details = Array.isArray(data.details) ? data.details : [];
  box.innerHTML = accKvGrid([
    { key: '总权益 totalEq', value: accMoney(data.totalEq) },
    { key: 'isoEq', value: accMoney(data.isoEq) },
    { key: 'adjEq', value: accMoney(data.adjEq) },
    { key: 'availEq', value: accMoney(data.availEq) },
    { key: '保证金率 mgnRatio', value: accNum(data.mgnRatio) },
    { key: '初始保证金 imr', value: accMoney(data.imr) },
    { key: '维持保证金 mmr', value: accMoney(data.mmr) },
    { key: '名义 notionalUsd', value: accMoney(data.notionalUsd) },
    { key: '更新时间 uTime', value: accTime(data.uTime) },
    { key: '币种数 details', value: accInt(details.length) }
  ]);
  if (!details.length) {
    if (wrap) setEmpty(wrap, '账户下暂无币种余额');
    return;
  }
  renderTable(wrap, ACC_BALANCE_COLS, details);
}

async function accountLoadPositions() {
  const wrap = byId('account-positions');
  pageLoading(wrap, '加载中…', !!ui.account.positions);
  let data;
  try {
    data = await apiGet('/api/v1/account/positions', { instType: 'SWAP' });
  } catch (err) {
    if (!ui.account.positions) setErrorState(wrap, '持仓加载失败：' + err.message);
    throw err;
  }
  ui.account.positions = data && typeof data === 'object' ? data : {};
  accRenderPositions(ui.account.positions);
}

function accRenderPositions(d) {
  const wrap = byId('account-positions');
  if (!wrap) return;
  const data = d || {};
  const issue = accIssueText(data);
  if (issue) {
    wrap.innerHTML = accAlertHtml(issue);
    return;
  }
  const items = Array.isArray(data.items) ? data.items : [];
  if (!items.length) {
    setEmpty(wrap, '当前无持仓');
    return;
  }
  renderTable(wrap, ACC_POSITION_COLS, items);
}

/* ===================== 16b. 我的订单（/api/v1/account/orders） ===================== */

const ORDERS_LIMIT = 50;
const ORDERS_EMPTY = {
  local: '本地暂无影子订单记录',
  pending: '交易所当前无挂单',
  history: '交易所无历史订单'
};

const ORDER_LOCAL_COLS = [
  { title: '时间 decidedAt', render: function (r) { return '<span class="mono">' + esc(accTime(r.decidedAt)) + '</span>'; } },
  { title: 'source', render: function (r) { return '<span class="mono">' + esc(isNil(r.source) ? ACC_NA : r.source) + '</span>'; } },
  { title: 'id', num: true, render: function (r) { return esc(accInt(r.id)); } },
  { title: '策略 strategyId', num: true, render: function (r) { return esc(accInt(r.strategyId)); } },
  { title: 'instId', render: function (r) { return '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>'; } },
  { title: '方向', render: function (r) { return sideCell(r.side); } },
  { title: '类型 ordType', render: function (r) { return esc(isNil(r.ordType) ? ACC_NA : r.ordType); } },
  { title: '数量 sz', num: true, render: function (r) { return esc(accQty(r.sz)); } },
  { title: '价格 px', num: true, render: function (r) { return esc(accPx(r.px)); } },
  { title: '名义 USD', num: true, render: function (r) { return esc(accMoney(r.notionalUsd)); } },
  { title: '状态 state', render: function (r) { return badge(r.state); } },
  { title: '原因 reason', render: function (r) { return esc(isNil(r.reason) ? ACC_NA : r.reason); } }
];

const ORDER_EX_COLS = [
  { title: '时间 cTime', render: function (r) { return '<span class="mono">' + esc(accTime(r.cTime)) + '</span>'; } },
  { title: 'instId', render: function (r) { return '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>'; } },
  { title: 'ordId', render: function (r) { return '<span class="mono">' + esc(isNil(r.ordId) ? ACC_NA : r.ordId) + '</span>'; } },
  { title: 'clOrdId', render: function (r) { return '<span class="mono">' + esc(isNil(r.clOrdId) ? ACC_NA : r.clOrdId) + '</span>'; } },
  { title: '方向', render: function (r) { return sideCell(r.side); } },
  { title: 'posSide', render: function (r) { return sideCell(r.posSide); } },
  { title: '类型 ordType', render: function (r) { return esc(isNil(r.ordType) ? ACC_NA : r.ordType); } },
  { title: '状态 state', render: function (r) { return badge(r.state); } },
  { title: '数量 sz', num: true, render: function (r) { return esc(accQty(r.sz)); } },
  { title: '已成交 fillSz', num: true, render: function (r) { return esc(accQty(r.fillSz)); } },
  { title: '价格 px', num: true, render: function (r) { return esc(accPx(r.px)); } },
  { title: '均价 avgPx', num: true, render: function (r) { return esc(accPx(r.avgPx)); } },
  { title: '手续费 fee', num: true, render: function (r) {
      if (isNil(r.fee)) return esc(ACC_NA);
      return esc(accQty(r.fee)) + (isNil(r.feeCcy) ? '' : ' <span class="muted">' + esc(r.feeCcy) + '</span>');
    } },
  { title: '盈亏 pnl', num: true, render: function (r) { return accPnl(r.pnl); } },
  { title: 'lever', num: true, render: function (r) { return esc(accNum(r.lever)); } },
  { title: '只减仓', render: function (r) { return esc(accBool(r.reduceOnly)); } },
  { title: 'algoId', render: function (r) { return '<span class="mono">' + esc(isNil(r.algoId) ? ACC_NA : r.algoId) + '</span>'; } },
  { title: '更新时间 uTime', render: function (r) { return '<span class="mono">' + esc(accTime(r.uTime)) + '</span>'; } }
];

function initOrdersPage() {
  if (!ui.orders.bound) {
    ui.orders.bound = true;
    const tabs = document.querySelectorAll('#orders-tabs .tab');
    for (let i = 0; i < tabs.length; i++) {
      tabs[i].addEventListener('click', function () {
        ordersSwitchTab(this.getAttribute('data-tab'));
      });
    }
    const refresh = byId('orders-refresh');
    if (refresh) refresh.addEventListener('click', function () { ordersLoadTab(ui.orders.tab, true); });
  }
  if (ui.route !== 'orders') return;
  ordersSwitchTab(ui.orders.tab || 'local');
}

function ordersSwitchTab(name) {
  const tab = name === 'pending' || name === 'history' ? name : 'local';
  ui.orders.tab = tab;
  const tabs = document.querySelectorAll('#orders-tabs .tab');
  for (let i = 0; i < tabs.length; i++) {
    const on = tabs[i].getAttribute('data-tab') === tab;
    if (on) tabs[i].classList.add('is-active');
    else tabs[i].classList.remove('is-active');
    tabs[i].setAttribute('aria-selected', on ? 'true' : 'false');
  }
  const panels = document.querySelectorAll('[data-page="orders"] .tab-panel');
  for (let j = 0; j < panels.length; j++) {
    if (panels[j].getAttribute('data-panel') === tab) panels[j].classList.add('is-active');
    else panels[j].classList.remove('is-active');
  }
  ordersLoadTab(tab, false);
}

async function ordersLoadTab(name, force) {
  if (ui.orders.loading[name]) return;
  if (!force && ui.orders.loaded[name]) return;
  const wrap = byId('orders-' + name + '-wrap');
  const meta = byId('orders-' + name + '-meta');
  const errBox = byId('orders-error');
  ui.orders.loading[name] = true;
  pageLoading(wrap, '加载中…', ui.orders.loaded[name]);
  if (meta && !ui.orders.loaded[name]) meta.textContent = '加载中…';
  let data;
  try {
    data = await apiGet('/api/v1/account/orders', { state: name, instType: 'SWAP', limit: ORDERS_LIMIT });
  } catch (err) {
    setErrorState(wrap, '订单加载失败：' + err.message);
    if (meta) meta.textContent = '加载失败';
    showError(errBox, '订单加载失败（state=' + name + '）：' + err.message);
    ui.orders.loading[name] = false;
    return;
  }
  ui.orders.loading[name] = false;
  ui.orders.loaded[name] = true;
  showError(errBox, '');
  const issue = accIssueText(data);
  const items = data && Array.isArray(data.items) ? data.items : [];
  if (!issue && data && !isNil(data.notice)) {
    // 凭证没配时后端只给 notice，这里也要让用户看到原因（否则只剩空态文案）。
    if (errBox) errBox.innerHTML = accAlertHtml(data.notice);
  } else if (errBox) {
    errBox.innerHTML = '';
  }
  if (issue) {
    if (wrap) wrap.innerHTML = accAlertHtml(issue);
    if (meta) meta.textContent = '接口返回 ok=false';
  } else if (!items.length) {
    setEmpty(wrap, ORDERS_EMPTY[name]);
    if (meta) meta.textContent = '共 0 条 · state=' + name + (name === 'local' ? '' : ' · instType=SWAP');
  } else {
    renderTable(wrap, name === 'local' ? ORDER_LOCAL_COLS : ORDER_EX_COLS, items);
    if (meta) {
      meta.textContent = '共 ' + items.length + ' 条 · state=' + name + (name === 'local' ? '' : ' · instType=SWAP');
    }
  }
  const updated = byId('orders-updated');
  if (updated) updated.textContent = '刷新于 ' + fmtTime(Date.now());
}

/* ===================== 16c. 马丁策略（/api/v1/strategy/strategies） ===================== */

const STRATEGIES_LIMIT = 50;
const STRATEGIES_EMPTY = {
  running: '暂无运行中的马丁策略',
  history: '暂无历史策略'
};

const STRATEGY_PARAM_LABELS = {
  side: '方向 side',
  leverage: '杠杆 leverage',
  maxLayers: '最大层数 maxLayers',
  layerScale: '加仓倍数 layerScale',
  layerStepPct: '加仓间距 % layerStepPct',
  takeProfitPct: '止盈 % takeProfitPct',
  stopLossPct: '止损 % stopLossPct',
  maxHoldHours: '最长持仓 小时 maxHoldHours',
  feeTaker: 'taker 费率 feeTaker',
  fundingPer8hPct: '8h 资金费率 % fundingPer8hPct',
  initialMarginUsd: '初始保证金 USD initialMarginUsd',
  slippageBps: '滑点 bps slippageBps'
};

const STRATEGY_POSITION_COLS = [
  { title: 'instId', render: function (r) { return '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>'; } },
  { title: 'side', render: function (r) { return sideCell(r.side); } },
  { title: 'sz', num: true, render: function (r) { return esc(accQty(r.sz)); } },
  { title: 'entryPx', num: true, render: function (r) { return esc(accPx(r.entryPx)); } },
  { title: 'markPx', num: true, render: function (r) { return esc(accPx(r.markPx)); } },
  { title: '名义 USD', num: true, render: function (r) { return esc(accMoney(r.notionalUsd)); } },
  { title: '未实现盈亏', num: true, render: function (r) { return accPnl(r.unrealizedPnl); } },
  { title: 'lever', num: true, render: function (r) { return esc(accNum(r.leverage)); } },
  { title: 'liqPx', num: true, render: function (r) { return esc(accPx(r.liqPx)); } },
  { title: '保证金 USD', num: true, render: function (r) { return esc(accMoney(r.marginUsd)); } },
  { title: '状态', render: function (r) { return badge(r.status); } },
  { title: '开仓时间', render: function (r) { return '<span class="mono">' + esc(accTime(r.openedAt)) + '</span>'; } }
];

const STRATEGY_ORDER_COLS = [
  { title: '时间 decidedAt', render: function (r) { return '<span class="mono">' + esc(accTime(r.decidedAt)) + '</span>'; } },
  { title: 'id', num: true, render: function (r) { return esc(accInt(r.id)); } },
  { title: 'instId', render: function (r) { return '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>'; } },
  { title: '方向', render: function (r) { return sideCell(r.side); } },
  { title: '类型 ordType', render: function (r) { return esc(isNil(r.ordType) ? ACC_NA : r.ordType); } },
  { title: '数量 sz', num: true, render: function (r) { return esc(accQty(r.sz)); } },
  { title: '价格 px', num: true, render: function (r) { return esc(accPx(r.px)); } },
  { title: '名义 USD', num: true, render: function (r) { return esc(accMoney(r.notionalUsd)); } },
  { title: '状态', render: function (r) { return badge(r.status); } },
  { title: '原因 reason', render: function (r) { return esc(isNil(r.reason) ? ACC_NA : r.reason); } }
];

function initStrategiesPage() {
  if (!ui.strategies.bound) {
    ui.strategies.bound = true;
    const tabs = document.querySelectorAll('#strategies-tabs .tab');
    for (let i = 0; i < tabs.length; i++) {
      tabs[i].addEventListener('click', function () {
        strategiesSwitchTab(this.getAttribute('data-tab'));
      });
    }
    const refresh = byId('strategies-refresh');
    if (refresh) refresh.addEventListener('click', function () { strategiesLoadTab(ui.strategies.tab, true); });
    const section = document.querySelector('[data-page="strategies"]');
    if (section) section.addEventListener('click', strategiesOnClick);
  }
  if (ui.route !== 'strategies') return;
  strategiesSwitchTab(ui.strategies.tab || 'running');
}

function strategiesSwitchTab(name) {
  const tab = name === 'history' ? 'history' : 'running';
  ui.strategies.tab = tab;
  const tabs = document.querySelectorAll('#strategies-tabs .tab');
  for (let i = 0; i < tabs.length; i++) {
    const on = tabs[i].getAttribute('data-tab') === tab;
    if (on) tabs[i].classList.add('is-active');
    else tabs[i].classList.remove('is-active');
    tabs[i].setAttribute('aria-selected', on ? 'true' : 'false');
  }
  const panels = document.querySelectorAll('[data-page="strategies"] .tab-panel');
  for (let j = 0; j < panels.length; j++) {
    if (panels[j].getAttribute('data-panel') === tab) panels[j].classList.add('is-active');
    else panels[j].classList.remove('is-active');
  }
  strategiesCollapseDetail();
  strategiesLoadTab(tab, false);
}

async function strategiesLoadTab(name, force) {
  if (ui.strategies.loading[name]) return;
  if (!force && ui.strategies.loaded[name]) return;
  const wrap = byId('strategies-' + name + '-wrap');
  const meta = byId('strategies-' + name + '-meta');
  const errBox = byId('strategies-error');
  ui.strategies.loading[name] = true;
  pageLoading(wrap, '加载中…', ui.strategies.loaded[name]);
  if (meta && !ui.strategies.loaded[name]) meta.textContent = '加载中…';
  let data;
  try {
    data = await apiGet('/api/v1/strategy/strategies', { state: name, limit: STRATEGIES_LIMIT });
  } catch (err) {
    setErrorState(wrap, '策略加载失败：' + err.message);
    if (meta) meta.textContent = '加载失败';
    showError(errBox, '策略加载失败（state=' + name + '）：' + err.message);
    ui.strategies.loading[name] = false;
    return;
  }
  ui.strategies.loading[name] = false;
  ui.strategies.loaded[name] = true;
  showError(errBox, '');
  const d = data && typeof data === 'object' ? data : {};
  ui.strategies.rows[name] = Array.isArray(d.items) ? d.items : [];
  if (name === 'running') {
    const def = byId('strategies-running-def');
    if (def) {
      def.textContent = isNil(d.runningDefinition) ? '口径：' + ACC_NA : '口径：' + String(d.runningDefinition);
    }
  }
  if (meta) {
    meta.textContent = ui.strategies.rows[name].length
      ? ('共 ' + ui.strategies.rows[name].length + ' 个 · limit=' + STRATEGIES_LIMIT)
      : '共 0 个';
  }
  strategiesRenderRows(name);
  const updated = byId('strategies-updated');
  if (updated) updated.textContent = '刷新于 ' + fmtTime(Date.now());
}

function strategiesRenderRows(name) {
  const wrap = byId('strategies-' + name + '-wrap');
  if (!wrap) return;
  const list = ui.strategies.rows[name] || [];
  if (!list.length) {
    setEmpty(wrap, STRATEGIES_EMPTY[name]);
    return;
  }
  let html = '<table><thead><tr>' +
    '<th>id</th><th>name</th><th>status</th><th>mode</th><th class="num">持仓（开/平）</th>' +
    '<th class="num">持仓名义 USD</th><th class="num">未实现盈亏 USD</th><th class="num">订单数</th>' +
    '<th>最近下单</th><th>创建时间</th></tr></thead><tbody>';
  for (let i = 0; i < list.length; i++) {
    const r = list[i] || {};
    const on = Number(r.id) === Number(ui.strategies.expandedId);
    html += '<tr class="row-link' + (on ? ' is-selected' : '') + '" data-strategy-id="' + esc(r.id) + '">';
    html += '<td class="mono">' + esc(isNil(r.id) ? ACC_NA : r.id) + '</td>';
    html += '<td>' + esc(isNil(r.name) ? ACC_NA : r.name) + '</td>';
    html += '<td>' + badge(r.status) + '</td>';
    html += '<td>' + esc(isNil(r.mode) ? ACC_NA : r.mode) + '</td>';
    html += '<td class="num">' + esc(accInt(r.openPositions)) + ' / ' + esc(accInt(r.closedPositions)) + '</td>';
    html += '<td class="num">' + esc(accMoney(r.openNotionalUsd)) + '</td>';
    html += '<td class="num">' + accPnl(r.unrealizedPnlUsd) + '</td>';
    html += '<td class="num">' + esc(accInt(r.orderCount)) + '</td>';
    html += '<td class="mono">' + esc(accTime(r.lastOrderAt)) + '</td>';
    html += '<td class="mono">' + esc(accTime(r.createdAt)) + '</td>';
    html += '</tr>';
  }
  html += '</tbody></table>';
  wrap.innerHTML = html;
}

function strategiesOnClick(e) {
  const row = e.target && e.target.closest ? e.target.closest('[data-strategy-id]') : null;
  if (!row) return;
  const id = Number(row.getAttribute('data-strategy-id'));
  if (!isFinite(id) || id <= 0) return;
  strategiesToggleDetail(id);
}

function strategiesMarkRows() {
  const rows = document.querySelectorAll('[data-page="strategies"] [data-strategy-id]');
  for (let i = 0; i < rows.length; i++) {
    const on = Number(rows[i].getAttribute('data-strategy-id')) === Number(ui.strategies.expandedId);
    if (on) rows[i].classList.add('is-selected');
    else rows[i].classList.remove('is-selected');
  }
}

function strategiesCollapseDetail() {
  ui.strategies.expandedId = 0;
  ui.strategies.detail = null;
  const card = byId('strategies-detail-card');
  if (card) card.hidden = true;
  strategiesMarkRows();
}

async function strategiesToggleDetail(id) {
  if (Number(ui.strategies.expandedId) === Number(id)) {
    strategiesCollapseDetail();
    return;
  }
  ui.strategies.expandedId = id;
  ui.strategies.detail = null;
  const card = byId('strategies-detail-card');
  const box = byId('strategies-detail');
  const title = byId('strategies-detail-title');
  const path = byId('strategies-detail-path');
  if (title) title.textContent = '#' + id;
  if (path) path.textContent = 'GET /api/v1/strategy/strategies/' + id;
  if (card) card.hidden = false;
  setLoading(box, '加载中…');
  strategiesMarkRows();
  let data;
  try {
    data = await apiGet('/api/v1/strategy/strategies/' + id);
  } catch (err) {
    if (Number(ui.strategies.expandedId) === Number(id)) setErrorState(box, '详情加载失败：' + err.message);
    return;
  }
  if (Number(ui.strategies.expandedId) !== Number(id)) return;
  ui.strategies.detail = data;
  strategiesRenderDetail(data, id);
  if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function strategiesRenderDetail(d, id) {
  const box = byId('strategies-detail');
  if (!box) return;
  const s = d && typeof d === 'object' ? d : {};
  const params = s.params && typeof s.params === 'object' ? s.params : {};
  const universe = s.universe && Array.isArray(s.universe.instIds) ? s.universe.instIds : [];
  // 持仓明细：新契约用 positions[]（openPositions 已变成开仓数量）；兼容旧契约里 openPositions 为数组的返回
  const openPositions = Array.isArray(s.positions) ? s.positions
    : (Array.isArray(s.openPositions) ? s.openPositions : []);
  const orders = Array.isArray(s.recentOrders) ? s.recentOrders : [];
  let html = accKvGrid([
    { key: '策略 id', html: '<span class="mono">#' + esc(id) + '</span>' },
    { key: '名称 name', value: s.name },
    { key: '状态 status', html: badge(s.status) },
    { key: '模式 mode', value: s.mode },
    { key: '运行中 running', value: s.running },
    { key: '单币资金 USD', value: accMoney(s.capitalPerCoinUsd) },
    { key: '来源回测 run', value: s.sourceRunId },
    { key: '备注 note', value: s.note },
    { key: '未实现盈亏 USD', html: accPnl(s.unrealizedPnlUsd) },
    { key: '订单数', value: accInt(s.orderCount) },
    { key: '最近下单', value: accTime(s.lastOrderAt) },
    { key: '创建时间', value: accTime(s.createdAt) },
    { key: '更新时间', value: accTime(s.updatedAt) }
  ]);

  const pKeys = Object.keys(params);
  html += '<div class="detail-block"><h4>参数 params（' + pKeys.length + '）</h4>';
  if (!pKeys.length) {
    html += '<p class="hint">' + ACC_NA + '</p>';
  } else {
    const pairs = [];
    for (let i = 0; i < pKeys.length; i++) {
      const k = pKeys[i];
      pairs.push({ key: STRATEGY_PARAM_LABELS[k] || k, value: accNum(params[k]) });
    }
    html += accKvGrid(pairs);
  }
  html += '</div>';

  html += '<div class="detail-block"><h4>自选合约 universe（' + universe.length + '）</h4>';
  if (!universe.length) {
    html += '<p class="hint">' + ACC_NA + '</p>';
  } else {
    html += '<div class="chip-row">';
    for (let u = 0; u < universe.length; u++) {
      html += '<span class="chip-static mono">' + esc(universe[u]) + '</span>';
    }
    html += '</div>';
  }
  html += '</div>';

  html += '<div class="detail-block"><h4>当前持仓 positions（' + openPositions.length + '）</h4>' +
    accTableHtml(STRATEGY_POSITION_COLS, openPositions, '无持仓') + '</div>';
  html += '<div class="detail-block"><h4>最近订单 recentOrders（' + orders.length + '）</h4>' +
    accTableHtml(STRATEGY_ORDER_COLS, orders, '该策略暂无订单') + '</div>';
  box.innerHTML = html;
}

/* ======================= 15. 启动 ======================= */

function initQuickChips() {
  document.addEventListener('click', function (e) {
    const target = e.target;
    if (!target || !target.closest) return;
    const chip = target.closest('.chip');
    if (!chip) return;
    const days = chip.getAttribute('data-quick-days');
    const clear = chip.getAttribute('data-quick-clear');
    if (!days && !clear) return;
    e.preventDefault();
    const form = chip.closest('form');
    if (!form) return;
    if (days) setQuickRange(form, Number(days));
    else clearQuickRange(form);
  });
}

function initResizeHandler() {
  let timer = 0;
  window.addEventListener('resize', function () {
    if (ui.route !== 'klines' && ui.route !== 'strategy-backtest' && ui.route !== 'shadow') return;
    if (timer) clearTimeout(timer);
    timer = setTimeout(function () {
      timer = 0;
      if (ui.route === 'klines') {
        drawChart(ui.klines.chartRows, ui.klines.chartInfo);
      } else if (ui.route === 'strategy-backtest') {
        drawBtEquity();
      } else if (ui.route === 'shadow') {
        shadowDrawEquity();
      }
    }, 150);
  });
}

function init() {
  initTopbar();
  initRouter();
  initStatusPage();
  initKlinePage();
  initFundingPage();
  initStatsPage();
  initBackfillPage();
  initJobsPage();
  initSyncPage();
  initDataPage();
  initNavGroups();
  initStrategyBacktestPage();
  initShadowPage();
  initHealthPage();
  initLivePage();
  initAccountPage();
  initOrdersPage();
  initStrategiesPage();
  initQuickChips();
  initResizeHandler();
  applyRoute();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
