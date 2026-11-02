<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { apiGet, apiPost } from '../api/client'
import { EP } from '../api/endpoints'
import { badge, esc, fmtInt, pagerInfo } from '../lib/format'
import { DATA_TYPES, DATA_TYPE_LABELS, JOB_STATUSES, JOB_STATUS_LABELS } from '../lib/labels'
import { useUiStore } from '../stores/ui'
import { usePolling } from '../composables/usePolling'
import StateBlock from '../components/StateBlock.vue'
import {
  JOB_ACTION_LABELS,
  JOB_LIMITS,
  canCancel,
  canPause,
  canResume,
  createdAtText,
  dataTypeText,
  jobsState,
  jobsSummary,
  listParams,
  progressOf,
  renderJobDetail,
  type BackfillJob,
  type JobDetailPayload
} from './jobs/jobs'

const ui = useUiStore()

/* legacy 的 ui.jobs 是模块级状态（offset/limit/loaded/expanded 缓存），跨路由保留 */
const state = reactive(jobsState)

const auto = ref(true)
const loading = ref(true)
const failed = ref(false)
const failedMsg = ref('')
const error = ref('')
const rows = ref<BackfillJob[]>([])
const summary = ref('尚未查询')
const limitSel = ref<number>(jobsState.limit)
const dataTypeSel = ref('')
const statusSel = ref('')

const dataTypeOptions = DATA_TYPES.map((d) => ({ value: d, label: d + '（' + (DATA_TYPE_LABELS[d] || d) + '）' }))
const statusOptions = JOB_STATUSES.map((s) => ({ value: s, label: s + '（' + (JOB_STATUS_LABELS[s] || s) + '）' }))

const pageInfo = ref('—')
const prevDisabled = computed(() => state.offset === 0)
const nextDisabled = computed(() => rows.value.length < state.limit)

function messageOf(err: unknown): string {
  const e = err as { message?: string } | null | undefined
  return e && e.message ? e.message : String(err)
}

/* legacy 没有并发保护：慢请求期间的翻页/刷新不能被丢掉。
   这里用自增序号只丢弃「已被更新一次加载取代」的旧响应，避免越晚返回的旧数据覆盖新数据。 */
let loadSeq = 0

async function loadJobs(silent: boolean): Promise<void> {
  const seq = ++loadSeq
  const offset = state.offset
  error.value = ''
  const rawLimit = Number(limitSel.value)
  state.limit = Number.isFinite(rawLimit) && rawLimit > 0 ? rawLimit : 50
  if (!silent) {
    loading.value = true
    failed.value = false
    failedMsg.value = ''
  }
  try {
    const data = await apiGet<any>(
      EP.backfillJobs,
      listParams(dataTypeSel.value, statusSel.value, state.limit, offset)
    )
    if (seq !== loadSeq) return
    const list: BackfillJob[] = Array.isArray(data) ? data : []
    rows.value = list
    state.loaded = true
    summary.value = jobsSummary(list.length, state.limit)
    pageInfo.value = pagerInfo(offset, list.length)
    failed.value = false
    ui.touchRefresh()
  } catch (err: unknown) {
    if (seq !== loadSeq) return
    const msg = messageOf(err)
    if (!silent) {
      failed.value = true
      failedMsg.value = msg
    }
    error.value = '加载作业列表失败：' + msg
    ui.toast('加载作业列表失败：' + msg, 'err')
  } finally {
    if (seq === loadSeq && !silent) loading.value = false
  }
}

function onFilter(): void {
  state.offset = 0
  void loadJobs(false)
}

function gotoPrev(): void {
  state.offset = Math.max(0, state.offset - state.limit)
  void loadJobs(false)
}

function gotoNext(): void {
  state.offset = state.offset + state.limit
  void loadJobs(false)
}

function refreshNow(): void {
  void loadJobs(false)
}

async function toggleDetail(rawId: unknown): Promise<void> {
  const id = String(rawId)
  if (state.expanded[id]) {
    delete state.expanded[id]
    return
  }
  try {
    const data = await apiGet<JobDetailPayload | null>(EP.backfillJob(id), { includeTasks: 'true' })
    state.expanded[id] = renderJobDetail(data)
  } catch (err: unknown) {
    ui.toast('加载作业详情失败：' + messageOf(err), 'err')
  }
}

async function runAction(rawId: unknown, action: string): Promise<void> {
  const id = String(rawId)
  const label = JOB_ACTION_LABELS[action] || action
  if (action === 'cancel') {
    const ok = await ui.confirmDialog(
      '取消作业',
      '<p>确认取消作业 <span class="mono">' + esc(id) + '</span>？</p>' +
        '<p class="muted">已写入的数据不会回滚，未执行的任务将被标记为取消。</p>',
      '确认取消'
    )
    if (!ok) return
  }
  try {
    await apiPost(EP.backfillJobAction(id, action), null)
    ui.toast('已' + label + '作业 ' + id, 'ok')
    delete state.expanded[id]
    void loadJobs(false)
  } catch (err: unknown) {
    ui.toast(label + '失败：' + messageOf(err), 'err')
  }
}

/* 进入页面即加载 + 默认开启 5 秒轮询（legacy onEnter + startJobsTimer） */
onMounted(() => {
  void loadJobs(false)
})

const polling = usePolling(() => loadJobs(true), 5000, { enabled: auto, immediate: false })

watch(auto, () => {
  polling.restart()
})
</script>

<template>
  <section class="page" data-page="jobs">
    <div class="page-head">
      <div>
        <h1>作业列表</h1>
        <p class="muted">GET /api/v1/backfill/jobs（按 jobId 倒序）。展开行可查看子任务，支持暂停 / 恢复 / 取消。</p>
      </div>
      <div class="page-actions">
        <label class="switch"><input v-model="auto" type="checkbox"><span>每 5 秒自动刷新</span></label>
        <button class="btn btn-primary" type="button" @click="refreshNow">刷新</button>
      </div>
    </div>

    <form class="card" id="form-jobs-filters" novalidate @submit.prevent="onFilter">
      <div class="form-grid form-grid-tight">
        <div class="field">
          <label for="jobs-datatype">dataType</label>
          <select id="jobs-datatype" v-model="dataTypeSel">
            <option value="">全部</option>
            <option v-for="o in dataTypeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </div>
        <div class="field">
          <label for="jobs-status">status</label>
          <select id="jobs-status" v-model="statusSel">
            <option value="">全部</option>
            <option v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </div>
        <div class="field">
          <label for="jobs-limit">每页条数</label>
          <select id="jobs-limit" v-model.number="limitSel">
            <option v-for="n in JOB_LIMITS" :key="n" :value="n">{{ n }}</option>
          </select>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group"></div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit">查询</button>
        </div>
      </div>
    </form>

    <div v-if="error" class="alert danger">{{ error }}</div>

    <div class="card">
      <div class="card-head"><h2>作业</h2><span class="muted">{{ summary }}</span></div>

      <StateBlock v-if="loading" kind="loading" text="加载中…" />
      <StateBlock v-else-if="failed && !rows.length" kind="error" :text="'加载失败：' + failedMsg" />
      <StateBlock v-else-if="!rows.length" kind="empty" text="暂无作业" />

      <table v-else class="table-jobs">
        <thead>
          <tr>
            <th>jobId</th>
            <th>dataType</th>
            <th>状态</th>
            <th>进度</th>
            <th class="num">写入行数</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="j in rows" :key="String(j.id)">
            <tr :data-job-id="j.id">
              <td class="mono">{{ j.id }}</td>
              <td>{{ dataTypeText(j) }}</td>
              <td v-html="badge(j.status)"></td>
              <td>
                <div class="progress-cell">
                  <div class="progress">
                    <div :class="['bar', progressOf(j).barClass]" :style="{ width: progressOf(j).pct + '%' }"></div>
                  </div>
                  <span class="progress-text">{{ progressOf(j).text }}</span>
                </div>
              </td>
              <td class="num">{{ fmtInt(j.rowsWritten) }}</td>
              <td class="mono">{{ createdAtText(j) }}</td>
              <td>
                <div class="row-actions">
                  <button class="btn btn-xs" type="button" :data-job-toggle="j.id" @click="toggleDetail(j.id)">详情</button>
                  <button
                    class="btn btn-xs"
                    type="button"
                    data-job-action="pause"
                    :data-job-id="j.id"
                    :disabled="!canPause(j)"
                    @click="runAction(j.id, 'pause')"
                  >暂停</button>
                  <button
                    class="btn btn-xs"
                    type="button"
                    data-job-action="resume"
                    :data-job-id="j.id"
                    :disabled="!canResume(j)"
                    @click="runAction(j.id, 'resume')"
                  >继续</button>
                  <button
                    class="btn btn-xs btn-danger"
                    type="button"
                    data-job-action="cancel"
                    :data-job-id="j.id"
                    :disabled="!canCancel(j)"
                    @click="runAction(j.id, 'cancel')"
                  >取消</button>
                </div>
              </td>
            </tr>
            <tr v-if="state.expanded[String(j.id)]" class="table-detail" :data-job-detail="j.id">
              <td colspan="7" v-html="state.expanded[String(j.id)]"></td>
            </tr>
          </template>
        </tbody>
      </table>

      <div class="pager">
        <button class="btn btn-sm" type="button" :disabled="prevDisabled" @click="gotoPrev">上一页</button>
        <span class="pager-info">{{ pageInfo }}</span>
        <button class="btn btn-sm" type="button" :disabled="nextDisabled" @click="gotoNext">下一页</button>
      </div>
    </div>
  </section>
</template>