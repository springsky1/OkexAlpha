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

/* 页面（hash 路由） */
const PAGES = ['status', 'klines', 'funding', 'stats', 'backfill', 'jobs', 'sync', 'data'];

const PAGE_TITLES = {
  status: '运行状态',
  klines: 'K 线查询',
  funding: '资金费率',
  stats: '统计查询',
  backfill: '补拉作业',
  jobs: '作业列表',
  sync: '同步',
  data: '数据浏览'
};

/* 图表字体与配色（与 styles.css 的 --up / --down 保持一致） */
const CHART_FONT = '11px ui-monospace, Menlo, Consolas, monospace';
const COLOR_UP = '#12a45c';
const COLOR_DOWN = '#e0533c';
const COLOR_GRID = '#e4e8ef';
const COLOR_AXIS = '#98a2b3';

/* ======================= 1. 通用工具 ======================= */

const ui = {
  route: '',
  timers: { status: 0, jobs: 0 },
  klines: { loaded: false, rows: [], chartRows: [], chartInfo: null, lastQuery: null, chartRatio: null },
  funding: { loaded: false },
  stats: { loaded: {} },
  coins: { loaded: false, limit: 50, offset: 0 },
  inst: { loaded: false, limit: 50, offset: 0 },
  jobs: { loaded: false, limit: 50, offset: 0, expanded: {} }
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

/* ======================= 14. 启动 ======================= */

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
    if (ui.route !== 'klines') return;
    if (timer) clearTimeout(timer);
    timer = setTimeout(function () {
      timer = 0;
      drawChart(ui.klines.chartRows, ui.klines.chartInfo);
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
  initQuickChips();
  initResizeHandler();
  applyRoute();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
