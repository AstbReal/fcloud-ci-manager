<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">审计日志</h2>
        <div class="fc-page-subtitle">
          保留 7 天；高危操作红色标记。流水线 API 调用不进审计。
        </div>
      </div>
      <div class="ph-actions">
        <button class="btn" @click="exportCsv">
          <ProtoIcon name="download" :size="15" />导出
        </button>
      </div>
    </div>

    <div class="filters">
      <FilterSelect
        :model-value="action"
        label="动作"
        all-label="全部动作"
        :options="actionOptions"
        @update:model-value="onAction"
      />

      <FilterSelect
        :model-value="userId"
        label="用户"
        all-label="全部用户"
        :options="userOptions"
        @update:model-value="onUser"
      />

      <div class="field mono date-field">
        <input v-model="from" placeholder="2026-10-12" maxlength="10" @change="reload" />
        <span class="muted">→</span>
        <input v-model="to" placeholder="2026-10-19" maxlength="10" @change="reload" />
      </div>

      <label class="critical-check">
        <input v-model="critical" type="checkbox" @change="reload" />
        <span class="checkbox" :class="{ on: critical }">
          <ProtoIcon name="check" :size="11" />
        </span>
        仅看高危
      </label>
    </div>

    <div class="card">
      <div ref="tableWrap" class="table-wrap">
        <table :style="{ minWidth: tableMinWidth + 'px' }">
          <colgroup>
            <col
              v-for="c in COLUMNS"
              :key="c.key"
              :style="widths[c.key] == null ? undefined : { width: widths[c.key] + 'px' }"
            />
            <!-- 兜底列：所有列都固定宽度后，由它吃掉窗口多出来的空间，
                 这样手动调过的列宽不会被浏览器按比例拉伸（详情列自适应时它宽度为 0） -->
            <col :style="widths.detail == null ? { width: '0px' } : undefined" />
          </colgroup>
          <thead>
            <tr>
              <th v-for="c in COLUMNS" :key="c.key">
                <span class="th-label">{{ c.label }}</span>
                <button
                  type="button"
                  class="col-grip"
                  role="separator"
                  aria-orientation="vertical"
                  :aria-label="`调整「${c.label}」列宽`"
                  title="拖动调整列宽 · 双击恢复默认 · ←→ 微调"
                  @pointerdown="startResize(c.key, $event, thWidth($event))"
                  @dblclick="resetResize(c.key)"
                  @keydown.left.prevent="stepResize(c.key, -16, thWidth($event))"
                  @keydown.right.prevent="stepResize(c.key, 16, thWidth($event))"
                  @keydown.home.prevent="resetResize(c.key)"
                />
              </th>
              <th class="th-filler" aria-hidden="true" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in logs" :key="row.id">
              <td class="mono time">{{ fmtShort(row.createdAt) }}</td>
              <td class="user-cell" :title="userText(row)">{{ userText(row) }}</td>
              <td>
                <span class="tag" :class="row.isCritical ? 'tag-danger' : 'tag-plain'">
                  {{ actionText(row.action) }}
                </span>
              </td>
              <td class="mono target" :title="row.targetId || row.targetType || ''">
                {{ row.targetId || row.targetType || '—' }}
              </td>
              <td class="detail-cell">
                <button
                  v-if="row.detail"
                  type="button"
                  class="detail-btn"
                  :title="`${detailPreview(row.detail)}\n\n点击查看完整详情`"
                  @click="openDetail(row)"
                >
                  <span class="detail-text mono">{{ detailPreview(row.detail) }}</span>
                  <span class="detail-more">展开</span>
                </button>
                <span v-else class="muted">—</span>
              </td>
              <td class="mono time">{{ row.ip || '—' }}</td>
              <td class="filler" aria-hidden="true" />
            </tr>
            <tr v-if="!logs.length">
              <td :colspan="COLUMNS.length + 1" class="empty-row">
                当前筛选条件下没有记录，试试放宽时间范围。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="card-foot">
        <span>共 {{ total }} 条 · 第 {{ page }} / {{ pages }} 页</span>
        <div class="foot-right">
          <button v-if="customized" class="btn btn-sm btn-ghost" @click="resetAll">
            恢复默认列宽
          </button>
          <div class="pager-btns">
            <button class="btn btn-sm" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
            <button class="btn btn-sm" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
          </div>
        </div>
      </div>
    </div>

    <el-drawer v-model="detailOpen" :size="drawerSize" :with-header="false" destroy-on-close>
      <div v-if="active" class="ad">
        <div class="ad-head">
          <div class="ad-title">
            <span class="tag" :class="active.isCritical ? 'tag-danger' : 'tag-plain'">
              {{ actionText(active.action) }}
            </span>
            <span class="mono ad-when">{{ fmtFull(active.createdAt) }}</span>
          </div>
          <button class="icon-btn" title="关闭" @click="detailOpen = false">
            <ProtoIcon name="x" :size="15" />
          </button>
        </div>

        <dl class="ad-meta">
          <dt>用户</dt>
          <dd>{{ userText(active) }}</dd>
          <dt>对象</dt>
          <dd class="mono">{{ active.targetId || active.targetType || '—' }}</dd>
          <dt>IP</dt>
          <dd class="mono">{{ active.ip || '—' }}</dd>
          <dt>日志 ID</dt>
          <dd class="mono">{{ active.id }}</dd>
        </dl>

        <div class="ad-sec-head">
          <span>详情</span>
          <button v-if="rawDetail" class="btn btn-sm" @click="copyDetail">
            <ProtoIcon name="copy" :size="13" />复制 JSON
          </button>
        </div>

        <div v-if="detailEntries.length" class="ad-kv">
          <div v-for="e in detailEntries" :key="e.key" class="ad-row">
            <div class="ad-k">
              <span class="mono">{{ e.key }}</span>
              <span v-if="e.label !== e.key" class="ad-k-label">{{ e.label }}</span>
            </div>
            <div class="ad-v" :class="{ mono: e.mono }">{{ e.value }}</div>
          </div>
        </div>

        <pre v-else-if="rawDetail" class="ad-raw">{{ rawDetail }}</pre>
        <div v-else class="ad-empty">这条记录没有额外详情。</div>

        <p class="ad-note">
          详情里只有可定位的标识，不记录变量值；高危操作会标红。
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type PageResult } from '@/api/http'
import { userApi, type UserItem } from '@/api'
import ProtoIcon from '@/components/ProtoIcon.vue'
import FilterSelect from '@/components/FilterSelect.vue'
import { useColumnResize, type ResizableColumn } from '@/composables/useColumnResize'
import type { Id } from '@/types/id'

interface AuditRow {
  id: Id
  userId?: number
  userEmail?: string
  action: string
  targetType?: string
  targetId?: string
  detail?: string
  isCritical: boolean
  ip?: string
  createdAt: string
}

const ACTION_LABEL: Record<string, string> = {
  LOGIN: '登录',
  LOGOUT: '退出登录',
  VARIABLE_CREATE: '新增变量',
  VARIABLE_UPDATE: '修改变量',
  VARIABLE_DELETE: '删除变量',
  VARIABLE_REVEAL: '查看加密明文',
  FILE_UPLOAD: '上传文件',
  FILE_DELETE: '删除文件',
  TOKEN_CREATE: '签发 Token',
  TOKEN_ISSUE: '签发 Token',
  MEMBER_ADD: '添加模块成员',
  MEMBER_REMOVE: '移除模块成员',
  MEMBER_ROLE_CHANGE: '修改成员角色',
  BACKUP_DOWNLOAD: '下载备份',
  SUBSCRIBE: '订阅共享模块',
  UNSUBSCRIBE: '取消订阅',
  MODULE_COPY: '复制模块',
  MODULE_GROUP: '调整模块分组',
  TOKEN_REVOKE: '吊销 Token',
  APP_CREATE: '创建应用',
  APP_UPDATE: '修改应用',
  APP_DELETE: '删除应用',
  MODULE_CREATE: '创建模块',
  MODULE_UPDATE: '修改模块',
  MODULE_DELETE: '删除模块',
  TEMPLATE_CREATE: '创建模板',
  TEMPLATE_UPDATE: '修改模板',
  TEMPLATE_DELETE: '删除模板',
  TEMPLATE_SYNC: '同步模板',
  BACKUP_CREATE: '创建备份',
  BACKUP_RESTORE: '恢复备份',
  ROLE_UPDATE: '修改角色',
  ENV_CREATE: '新建环境',
  ENV_UPDATE: '修改环境',
  ENV_DELETE: '删除环境',
  SETTING_UPDATE: '修改系统设置',
  FILE_PREVIEW: '查看文件内容',
  APP_IMPORT: '从 GitLab 导入应用'
}

const actionOptions = Object.entries(ACTION_LABEL).map(([value, label]) => ({
  value,
  label
}))

const logs = ref<AuditRow[]>([])
const users = ref<UserItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const action = ref('')
const userId = ref('')
const critical = ref(false)
const from = ref('')
const to = ref('')

/**
 * 列宽：除详情列外都固定，详情列自适应剩余宽度（1600 宽的屏幕上它自己就够放全文），
 * 一旦手动拖过就固定下来，双击分隔条可退回自适应。
 */
const COLUMNS: ResizableColumn[] = [
  { key: 'time', label: '时间', width: 130, min: 112 },
  { key: 'user', label: '用户', width: 170, min: 110 },
  { key: 'action', label: '动作', width: 170, min: 110 },
  { key: 'target', label: '对象', width: 185, min: 110 },
  { key: 'detail', label: '详情', width: null, min: 140 },
  { key: 'ip', label: 'IP', width: 110, min: 96 }
]

const { widths, customized, start: startResize, step: stepResize, reset: resetResize, resetAll } =
  useColumnResize('fcloud.audit.cols.v1', COLUMNS)

const tableWrap = ref<HTMLElement | null>(null)
/**
 * 表格最小宽度：固定列之和 + 详情列的保底宽度。
 * 只用于「窗口太窄时在卡片内横向滚动」，不是每列的硬宽度。
 */
const tableMinWidth = computed(
  () =>
    COLUMNS.reduce((sum, c) => sum + (widths.value[c.key] ?? c.min ?? 120), 0)
)

/** 拖拽起点取表头「实际渲染宽度」：自定义宽度可能被 min-width 顶大，从设定值起算会跳一下 */
function thWidth(ev: Event): number {
  const grip = ev.currentTarget as HTMLElement | null
  const th = grip?.closest('th')
  return th ? Math.round(th.getBoundingClientRect().width) : 0
}

const detailOpen = ref(false)
const active = ref<AuditRow | null>(null)
/** 窄屏抽屉铺满，避免 480px 在手机宽度上横向溢出 */
const drawerSize = ref('480px')

const rawDetail = computed(() => {
  const d = active.value?.detail
  if (!d) return ''
  try {
    return JSON.stringify(JSON.parse(d), null, 2)
  } catch {
    return d
  }
})

const DETAIL_LABEL: Record<string, string> = {
  moduleId: '模块',
  environmentId: '环境',
  fileKey: '文件',
  fileName: '文件',
  fileId: '文件 ID',
  version: '版本',
  size: '大小',
  prefix: '前缀',
  strategy: '策略',
  enabled: '启用',
  revealEnabled: '允许查看明文',
  writeEnabled: '允许写入',
  tokenChanged: 'Token 已换发',
  baseUrl: 'GitLab 地址',
  scopes: '权限范围',
  records: '记录数',
  files: '文件数'
}

const detailEntries = computed(() => {
  const d = active.value?.detail
  if (!d) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(d)
  } catch {
    return []
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
  return Object.entries(parsed as Record<string, unknown>).map(([key, v]) => {
    const value =
      v === null || v === undefined
        ? '—'
        : typeof v === 'object'
          ? JSON.stringify(v)
          : String(v)
    return {
      key,
      label: DETAIL_LABEL[key] || key,
      value,
      mono: typeof v === 'number' || key === 'fileKey' || key === 'prefix'
    }
  })
})

/** 列里只放一行预览：JSON 拆成 "k: v · k: v"，比原样吐 `{"moduleId":…` 好认 */
const previewCache = new Map<string, string>()
function detailPreview(detail: string): string {
  const hit = previewCache.get(detail)
  if (hit !== undefined) return hit
  let out = detail
  try {
    const parsed = JSON.parse(detail)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      out = Object.entries(parsed as Record<string, unknown>)
        .map(([k, v]) => `${DETAIL_LABEL[k] || k} ${v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
        .join(' · ')
    }
  } catch {
    /* 不是 JSON 就原样显示 */
  }
  previewCache.set(detail, out)
  return out
}

function openDetail(row: AuditRow) {
  active.value = row
  drawerSize.value = window.innerWidth < 560 ? '100%' : '480px'
  detailOpen.value = true
}

async function copyDetail() {
  try {
    await navigator.clipboard.writeText(rawDetail.value)
    ElMessage.success('详情已复制')
  } catch {
    ElMessage.warning('复制失败，请在抽屉里手动选择文本复制')
  }
}

function fmtFull(v: string) {
  const d = new Date(v)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const pages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const userOptions = computed(() =>
  users.value.map((u) => ({ value: String(u.id), label: u.email }))
)

function actionText(a: string) {
  return ACTION_LABEL[a] || a
}

function userText(row: AuditRow) {
  if (row.userEmail) return row.userEmail
  const u = users.value.find((x) => String(x.id) === String(row.userId))
  return u ? u.email : row.userId ? `用户 #${row.userId}` : '—'
}

function fmtShort(v: string) {
  const d = new Date(v)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function onAction(v: string) {
  action.value = v
  reload()
}

function onUser(v: string) {
  userId.value = v
  reload()
}

function reload() {
  page.value = 1
  void load()
}

function go(p: number) {
  if (p < 1 || p > pages.value) return
  page.value = p
  void load()
}

async function load() {
  const params: Record<string, unknown> = { page: page.value, size: size.value }
  if (action.value) params.action = action.value
  if (userId.value) params.userId = userId.value
  if (critical.value) params.critical = true
  const dateRe = /^\d{4}-\d{2}-\d{2}$/
  if (dateRe.test(from.value)) params.from = `${from.value}T00:00:00`
  if (dateRe.test(to.value)) params.to = `${to.value}T23:59:59`
  const res = await api.get<PageResult<AuditRow>>('/api/console/admin/audit-logs', params)
  logs.value = res.records
  total.value = res.total
  void markClippedDetails()
}

/**
 * 只有真的被截断的行才常显「展开」：靠 hover 才出现的话，
 * 1280 这种宽度下用户根本不知道后面还有内容；全显又会让每一行都多一截噪音。
 */
async function markClippedDetails() {
  await nextTick()
  const wrap = tableWrap.value
  if (!wrap) return
  wrap.querySelectorAll<HTMLElement>('.detail-btn').forEach((btn) => {
    const text = btn.querySelector<HTMLElement>('.detail-text')
    if (text) btn.classList.toggle('is-clipped', text.scrollWidth > text.clientWidth + 1)
  })
}

let wrapObserver: ResizeObserver | null = null
watch(widths, () => void markClippedDetails(), { deep: true })

function exportCsv() {
  const head = ['时间', '用户', '动作', '对象', '详情', 'IP']
  const lines = logs.value.map((r) => [
    fmtShort(r.createdAt),
    r.userEmail || '',
    actionText(r.action),
    r.targetId || r.targetType || '',
    (r.detail || '').replace(/"/g, '""'),
    r.ip || ''
  ])
  const csv = [head, ...lines]
    .map((cols) => cols.map((c) => `"${c}"`).join(','))
    .join('\n')
  const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `audit-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(async () => {
  await load()
  if (tableWrap.value) {
    wrapObserver = new ResizeObserver(() => void markClippedDetails())
    wrapObserver.observe(tableWrap.value)
  }
  try {
    users.value = await userApi.list()
  } catch {
    users.value = []
  }
})

onBeforeUnmount(() => {
  wrapObserver?.disconnect()
  wrapObserver = null
})
</script>

<style scoped>
.ph-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  padding-top: 2px;
}

.filters {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.field {
  height: 32px;
  border: 1px solid var(--border-2);
  border-radius: var(--r-sm);
  background: #fff;
  padding: 0 10px;
  font-size: 13px;
  color: var(--text-2);
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.field b {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
}

.date-field {
  gap: 8px;
  cursor: default;
}

.date-field input {
  border: 0;
  outline: none;
  background: transparent;
  font: inherit;
  font-family: var(--mono);
  font-size: 12px;
  color: var(--text-2);
  width: 92px;
}

.date-field input::placeholder {
  color: var(--text-3);
}

.critical-check {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: var(--text-2);
  cursor: pointer;
  user-select: none;
}

.critical-check input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.checkbox {
  width: 15px;
  height: 15px;
  border-radius: 4px;
  border: 1.5px solid var(--border-2);
  display: grid;
  place-items: center;
  color: transparent;
  background: #fff;
}

.checkbox.on {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
  overflow: hidden;
}

/* 列宽由 <colgroup> 决定；窗口窄于列宽之和时在卡片内横向滚动 */
.table-wrap {
  overflow-x: auto;
  overscroll-behavior-x: contain;
}

table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  table-layout: fixed;
}

th {
  position: relative;
  text-align: left;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-2);
  letter-spacing: 0.02em;
  background: var(--surface-2);
  padding: 0 12px;
  height: 36px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
  user-select: none;
}

/* 表头分隔条：默认隐形（设计规范里表头不加竖线），hover 表头才浮出来。
   热区压在列边界内侧——伸到表格外面会让 .table-wrap 多出 5px 可滚动宽度，
   一个没内容的横向滚动条就这么出现了 */
.col-grip {
  position: absolute;
  top: 6px;
  right: 0;
  width: 11px;
  height: calc(100% - 12px);
  padding: 0;
  border: 0;
  background: transparent;
  cursor: col-resize;
  z-index: 2;
  touch-action: none;
}

.col-grip::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  right: 0.5px;
  width: 1px;
  background: var(--border-2);
  opacity: 0;
  transition: opacity 0.12s;
}

th:hover .col-grip::after,
.col-grip:hover::after,
.col-grip:focus-visible::after,
.col-grip:active::after {
  opacity: 1;
}

.col-grip:hover::after,
.col-grip:focus-visible::after {
  background: var(--primary);
  width: 2px;
  right: -0.5px;
}

.col-grip:focus-visible {
  outline: none;
}

.th-label {
  pointer-events: none;
}

/* 兜底列：只用来占位吸收窗口多出的宽度，不画任何东西 */
.th-filler,
.filler {
  padding: 0;
}

td {
  padding: 0 12px;
  height: 44px;
  border-bottom: 1px solid #f0f1f5;
  font-size: 13px;
  vertical-align: middle;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

tbody tr:last-child td {
  border-bottom: 0;
}

tbody tr:hover td {
  background: #fbfcfe;
}

/* 拖拽时全表统一 col-resize 光标并禁止选中文本；body 上的类由 composable 加/删，
   scoped 样式选择不到 body，所以放在下方非 scoped 的样式块里 */

.time {
  font-size: 11.5px;
  color: var(--text-3);
}

.user-cell {
  font-size: 12.5px;
}

.target {
  font-size: 12px;
}

/* ---- 详情列：一行预览 + 展开 ---- */
.detail-cell {
  font-size: 12.5px;
}

.detail-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 28px;
  margin: 0 -6px;
  padding: 0 6px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--text-3);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.detail-btn:hover {
  background: var(--surface-3);
  color: var(--text-2);
}

.detail-btn:focus-visible {
  outline: 2px solid rgba(37, 99, 235, 0.45);
  outline-offset: -2px;
}

.detail-text {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

.detail-more {
  flex: 0 0 auto;
  font-size: 11.5px;
  color: var(--primary);
  opacity: 0;
  transition: opacity 0.12s;
}

/* 被截断的行常显「展开」，未截断的行 hover 才出现 */
.detail-btn.is-clipped .detail-more {
  opacity: 1;
  color: var(--text-3);
}

.detail-btn:hover .detail-more,
.detail-btn:focus-visible .detail-more {
  opacity: 1;
  color: var(--primary);
}

.mono {
  font-family: var(--mono);
}

.muted {
  color: var(--text-3);
}

.tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border-radius: 5px;
  font-size: 11.5px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
}

.tag-plain {
  background: var(--surface-3);
  color: var(--text-2);
}

.tag-danger {
  background: var(--danger-50);
  color: var(--danger);
  border: 1px solid #f8d4d4;
}

.card-foot {
  padding: 11px 16px;
  border-top: 1px solid var(--border);
  font-size: 12.5px;
  color: var(--text-3);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.foot-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.pager-btns {
  display: flex;
  gap: 6px;
}

.empty-row {
  height: 100px;
  text-align: center;
  color: var(--text-3);
  font-size: 12.5px;
}

/* ---- 详情抽屉 ---- */
.ad {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-bottom: 8px;
}

.ad-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}

.ad-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.ad-when {
  font-size: 12px;
  color: var(--text-3);
}

.ad-meta {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
  font-size: 12.5px;
}

.ad-meta dt {
  color: var(--text-3);
}

.ad-meta dd {
  margin: 0;
  color: var(--text-2);
  overflow-wrap: anywhere;
}

.ad-sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-2);
  padding-top: 4px;
}

.ad-kv {
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.ad-row {
  display: grid;
  grid-template-columns: minmax(120px, 38%) minmax(0, 1fr);
  gap: 12px;
  padding: 9px 12px;
  border-bottom: 1px solid #f0f1f5;
  font-size: 12.5px;
}

.ad-row:last-child {
  border-bottom: 0;
}

.ad-row:nth-child(odd) {
  background: var(--surface-2);
}

.ad-k {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: var(--text-2);
}

.ad-k-label {
  font-size: 11px;
  color: var(--text-3);
}

.ad-v {
  color: var(--text);
  overflow-wrap: anywhere;
}

.ad-raw {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  font-family: var(--mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-2);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.ad-empty {
  padding: 20px 0;
  font-size: 12.5px;
  color: var(--text-3);
}

.ad-note {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-3);
}
</style>

<style>
/* 拖拽列宽时给整个文档上 col-resize 光标（scoped 选择不到 body） */
body.col-resizing,
body.col-resizing * {
  cursor: col-resize !important;
  user-select: none !important;
}
</style>
