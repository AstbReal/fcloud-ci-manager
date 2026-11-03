<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">模块</h2>
        <div class="fc-page-subtitle">
          按应用聚合，一行一个应用；点模块 chip 进入变量矩阵。
        </div>
      </div>
      <div class="ph-actions">
        <button class="btn btn-primary" @click="openCreate">
          <ProtoIcon name="plus" :size="15" />新建模块
        </button>
      </div>
    </div>

    <div class="filters">
      <label class="field search-field">
        <ProtoIcon name="search" :size="14" style="color: #8b93a1" />
        <input v-model="q" placeholder="搜应用 / 模块名 / code" />
      </label>

      <FilterSelect
        :model-value="appFilter"
        label="应用"
        all-label="全部应用"
        :options="appOptions"
        @update:model-value="setApp"
      />

      <FilterSelect
        v-model="groupFilter"
        label="分组"
        all-label="全部分组"
        :options="groupFilterOptions"
      />

      <button class="btn btn-ghost btn-sm manage-groups" @click="openGroupDialog">
        <ProtoIcon name="sliders" :size="14" />管理分组
      </button>

      <span class="filter-stat">
        {{ groups.length }} 个应用 · {{ filtered.length }} 个模块
      </span>
    </div>

    <div class="card">
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th style="width: 260px">应用</th>
              <th>模块</th>
              <th style="width: 130px">最近更新</th>
              <th style="width: 80px"></th>
            </tr>
          </thead>
          <tbody>
            <template v-for="g in groups" :key="g.key">
              <tr class="app-row" :class="{ 'is-open': isOpen(g.key) }" @click="toggle(g.key)">
                <td>
                  <div class="app-cell">
                    <span class="chev" :class="{ open: isOpen(g.key) }">
                      <ProtoIcon name="chevron" :size="13" />
                    </span>
                    <div class="app-main">
                      <div class="app-code">
                        <span class="mono t-strong">{{ g.code }}</span>
                        <span v-if="g.isShared" class="tag tag-shared">共享</span>
                      </div>
                      <div class="app-name">{{ g.name }}</div>
                    </div>
                  </div>
                </td>
                <td class="chips-cell">
                  <div class="chips">
                    <button
                      v-for="m in g.modules"
                      :key="m.id"
                      type="button"
                      class="chip"
                      :title="chipTitle(m)"
                      @click.stop="open(m)"
                    >
                      <span class="chip-code mono">{{ m.code }}</span>
                      <span class="chip-n" :class="{ zero: !m.variableCount }">
                        {{ m.variableCount }} 变量
                      </span>
                    </button>
                  </div>
                </td>
                <td class="muted">{{ relative(g.updatedAt) }}</td>
                <td class="row-act" @click.stop>
                  <button class="btn btn-ghost btn-sm" @click="toggle(g.key)">
                    {{ isOpen(g.key) ? '收起' : '明细' }}
                  </button>
                </td>
              </tr>

              <tr v-if="isOpen(g.key)" class="detail-row">
                <td colspan="4">
                  <div class="detail">
                    <div v-for="m in g.modules" :key="m.id" class="mod-line">
                      <button type="button" class="mod-id" @click="open(m)">
                        <span class="mono t-strong">{{ m.code }}</span>
                        <span class="mod-name">{{ m.name }}</span>
                      </button>
                      <span class="mod-grp">
                        <span v-if="m.groupName" class="tag tag-plain">{{ m.groupName }}</span>
                        <span v-else class="muted">未分组</span>
                      </span>
                      <span class="mod-meta">
                        {{ m.variableCount }} 变量 · {{ m.environmentCount }} 环境
                      </span>
                      <span class="mod-meta">{{ relative(m.updatedAt) }}</span>
                      <span class="mod-act">
                        <button class="btn btn-ghost btn-sm" @click.stop="openEdit(m)">
                          编辑
                        </button>
                        <button
                          v-if="isAdmin"
                          class="btn btn-ghost btn-sm danger"
                          @click.stop="remove(m)"
                        >
                          删除
                        </button>
                      </span>
                    </div>
                  </div>
                </td>
              </tr>
            </template>

            <tr v-if="!groups.length">
              <td colspan="4" class="empty-row">
                没有匹配的模块，试试换个关键词或应用。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <el-dialog v-model="groupDialog" title="管理分组" width="520px">
      <div class="group-list">
        <div v-for="g in dialogGroups" :key="g.name" class="group-row">
          <span class="g-name">{{ g.name }}</span>
          <span class="g-count">{{ g.count }} 个模块 · {{ g.apps }} 个应用</span>
          <button class="btn btn-ghost btn-sm" @click="renameGroup(g.name)">重命名</button>
          <button class="btn btn-ghost btn-sm danger" @click="dissolveGroup(g.name)">
            解散
          </button>
        </div>
        <div v-if="!dialogGroups.length" class="group-empty">
          还没有分组。编辑任意模块，在「分组名」里输入新名称即可创建。
        </div>
      </div>

      <p class="dialog-note">
        分组是全局词表，不区分应用：重命名会改掉所有应用下的同名分组；解散只是把模块移回「未分组」，不删模块。
      </p>

      <template #footer>
        <button class="btn" @click="groupDialog = false">关闭</button>
      </template>
    </el-dialog>

    <ModuleFormDrawer
      v-model="formOpen"
      :kind="editing?.isShared ? 'shared' : 'private'"
      :app-id="formAppId"
      :module="editing"
      :environments="envs"
      :apps="apps"
      :groups="groupNames"
      @saved="load"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { api } from '@/api/http'
import { envApi, type Env } from '@/api'
import ProtoIcon from '@/components/ProtoIcon.vue'
import FilterSelect from '@/components/FilterSelect.vue'
import ModuleFormDrawer from '@/components/ModuleFormDrawer.vue'
import type { Id } from '@/types/id'

interface ModuleRow {
  id: Id
  code: string
  name: string
  description?: string
  isShared: boolean
  groupName?: string
  applicationId?: Id
  applicationCode?: string
  applicationName?: string
  variableCount: number
  environmentCount: number
  updatedAt?: string
}

interface AppGroupRow {
  key: string
  code: string
  name: string
  isShared: boolean
  modules: ModuleRow[]
  updatedAt?: string
}

const router = useRouter()
const modules = ref<ModuleRow[]>([])
const apps = ref<Array<{ id: Id; code: string; name: string }>>([])
const envs = ref<Env[]>([])
const userStore = useUserStore()
const isAdmin = computed(() => userStore.isSystemAdmin())
const q = ref('')
const appFilter = ref('')
const groupFilter = ref('')
const groupDialog = ref(false)
const formOpen = ref(false)
const formAppId = ref<Id | undefined>()
const editing = ref<ModuleRow | null>(null)
const expandedKeys = ref<string[]>([])

const appOptions = computed(() => [
  { value: '__shared', label: '共享模块库' },
  ...apps.value.map((a) => ({ value: String(a.id), label: a.code }))
])

const groupNames = computed(() => {
  const set = new Set<string>()
  for (const m of modules.value) {
    if (m.groupName) set.add(m.groupName)
  }
  return Array.from(set).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const groupFilterOptions = computed(() => [
  { value: '__none', label: '未分组' },
  ...groupNames.value.map((g) => ({ value: g, label: g }))
])

const dialogGroups = computed(() => {
  const map = new Map<string, { count: number; apps: Set<string> }>()
  for (const m of modules.value) {
    if (m.isShared || !m.groupName) continue
    if (!map.has(m.groupName)) {
      map.set(m.groupName, { count: 0, apps: new Set() })
    }
    const item = map.get(m.groupName)!
    item.count += 1
    item.apps.add(String(m.applicationId ?? ''))
  }
  return Array.from(map.entries())
    .map(([name, v]) => ({ name, count: v.count, apps: v.apps.size }))
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
})

const filtered = computed(() =>
  modules.value.filter((m) => {
    if (appFilter.value === '__shared' && !m.isShared) return false
    if (appFilter.value && appFilter.value !== '__shared') {
      if (String(m.applicationId) !== appFilter.value) return false
    }
    if (groupFilter.value === '__none' && m.groupName) return false
    if (groupFilter.value && groupFilter.value !== '__none') {
      if (m.groupName !== groupFilter.value) return false
    }
    const kw = q.value.trim().toLowerCase()
    if (!kw) return true
    return (
      m.code.toLowerCase().includes(kw) ||
      (m.name || '').toLowerCase().includes(kw) ||
      (m.applicationCode || '').toLowerCase().includes(kw) ||
      (m.applicationName || '').toLowerCase().includes(kw)
    )
  })
)

/**
 * 按应用聚合成行：180 个模块压成 60 行，build / scc / image 变成行内 chip，
 * 不再是重复 60 次的同一段文字。
 */
const groups = computed<AppGroupRow[]>(() => {
  const order = new Map<string, number>()
  apps.value.forEach((a, i) => order.set(String(a.id), i))

  const map = new Map<string, AppGroupRow>()
  for (const m of filtered.value) {
    const key = m.isShared ? '__shared' : String(m.applicationId ?? '__orphan')
    let g = map.get(key)
    if (!g) {
      g = {
        key,
        code: m.isShared ? '共享模块库' : m.applicationCode || '未归属应用',
        name: m.isShared ? '跨应用共享，改动实时同步' : m.applicationName || '',
        isShared: !!m.isShared,
        modules: [],
        updatedAt: undefined
      }
      map.set(key, g)
    }
    g.modules.push(m)
    if (m.updatedAt && (!g.updatedAt || Date.parse(m.updatedAt) > Date.parse(g.updatedAt))) {
      g.updatedAt = m.updatedAt
    }
  }

  return Array.from(map.values()).sort((a, b) => {
    if (a.isShared !== b.isShared) return a.isShared ? -1 : 1
    const ai = order.get(a.key) ?? Number.MAX_SAFE_INTEGER
    const bi = order.get(b.key) ?? Number.MAX_SAFE_INTEGER
    if (ai !== bi) return ai - bi
    return a.code.localeCompare(b.code, 'zh-CN')
  })
})

function isOpen(key: string) {
  return expandedKeys.value.includes(key)
}

function toggle(key: string) {
  const i = expandedKeys.value.indexOf(key)
  if (i >= 0) expandedKeys.value.splice(i, 1)
  else expandedKeys.value.push(key)
}

function chipTitle(m: ModuleRow) {
  return [
    m.name,
    m.groupName ? `分组 ${m.groupName}` : '未分组',
    `${m.variableCount} 个变量 · ${m.environmentCount} 个环境`
  ].join(' · ')
}

function relative(v?: string) {
  if (!v) return '—'
  const diff = Date.now() - new Date(v).getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day === 1) return '昨天'
  if (day < 30) return `${day} 天前`
  return new Date(v).toLocaleDateString('zh-CN').replace(/\//g, '-')
}

function open(m: ModuleRow) {
  router.push(`/modules/${m.id}`)
}

function openCreate() {
  formAppId.value = appFilter.value && appFilter.value !== '__shared'
    ? appFilter.value
    : undefined
  editing.value = null
  formOpen.value = true
}

function openEdit(m: ModuleRow) {
  formAppId.value = m.applicationId
  editing.value = m
  formOpen.value = true
}

async function remove(m: ModuleRow) {
  const extra = m.isShared
    ? '共享模块若仍有订阅方，需要先取消订阅才能删除。'
    : '模块的变量、文件与成员授权会一并删除。'
  try {
    await ElMessageBox.confirm(
      `删除模块「${m.code}」？${extra}`,
      '删除模块',
      { type: 'warning', confirmButtonText: '删除' }
    )
    await api.del(`/api/console/admin/modules/${m.id}`)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 取消或后端拒绝（仍有订阅方）
  }
}

function setApp(v: string) {
  appFilter.value = v
  groupFilter.value = ''
  expandedKeys.value = []
}

function openGroupDialog() {
  groupDialog.value = true
}

async function renameGroup(from: string) {
  try {
    const { value } = await ElMessageBox.prompt(
      `重命名分组「${from}」，所有应用下的同名分组会一起改。`,
      '重命名分组',
      { inputValue: from, confirmButtonText: '重命名' }
    )
    const to = (value || '').trim()
    if (!to || to === from) return
    await api.put('/api/console/admin/modules/group', { from, to })
    ElMessage.success('已重命名')
    groupFilter.value = ''
    await load()
  } catch {
    // 取消
  }
}

async function dissolveGroup(from: string) {
  try {
    await ElMessageBox.confirm(
      `把「${from}」下的模块移回「未分组」？模块本身不会删除。`,
      '解散分组',
      { type: 'warning', confirmButtonText: '移回未分组' }
    )
    await api.put('/api/console/admin/modules/group', { from, to: null })
    ElMessage.success('已移回未分组')
    groupFilter.value = ''
    await load()
  } catch {
    // 取消
  }
}

async function load() {
  modules.value = await api.get<ModuleRow[]>('/api/console/admin/modules')
}

onMounted(async () => {
  // 三个接口并行取、一次性赋值：模块先到、应用后到会导致分组顺序先按 code 排一遍
  // 再重排一次，页面上能看到行序跳动。
  try {
    const [m, a, e] = await Promise.all([
      api.get<ModuleRow[]>('/api/console/admin/modules'),
      api.get<Array<{ id: Id; code: string; name: string }>>(
        '/api/console/admin/applications'
      ),
      envApi.list()
    ])
    modules.value = m
    apps.value = a
    envs.value = e
  } catch {
    // 接口未就绪时保留空态，不阻塞页面
  }
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

.search-field {
  width: 260px;
  gap: 7px;
}

.search-field input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  font: inherit;
  font-size: 13px;
  color: var(--text);
}

.search-field input::placeholder {
  color: var(--text-3);
}

.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.manage-groups {
  margin-left: 2px;
}

.filter-stat {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.group-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 320px;
  overflow: auto;
}

.group-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.g-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 500;
}

.g-count {
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.group-empty {
  padding: 20px 8px;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-3);
}

.btn-ghost.danger {
  color: var(--danger);
}

.btn-ghost.danger:hover {
  background: var(--danger-50);
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
}

.table-scroll {
  overflow-x: auto;
}

table {
  width: 100%;
  min-width: 720px;
  border-collapse: separate;
  border-spacing: 0;
}

th {
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
}

td {
  padding: 0 12px;
  height: 52px;
  border-bottom: 1px solid #f0f1f5;
  font-size: 13px;
  vertical-align: middle;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

tbody tr.app-row {
  cursor: pointer;
}

tbody tr:last-child td,
tbody tr.detail-row:last-child td {
  border-bottom: 0;
}

tbody tr.app-row:hover td {
  background: #fbfcfe;
}

tbody tr.app-row.is-open td {
  background: #fbfcfe;
  border-bottom-color: transparent;
}

.app-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.chev {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  color: var(--text-3);
  transform: rotate(-90deg);
  transition: transform 0.15s ease;
  flex: 0 0 16px;
}

.chev.open {
  transform: rotate(0deg);
  color: var(--text-2);
}

.app-main {
  min-width: 0;
}

.app-code {
  display: flex;
  align-items: center;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-name {
  margin-top: 1px;
  font-size: 11.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
}

.chips-cell {
  height: auto;
  padding-top: 9px;
  padding-bottom: 9px;
  white-space: normal;
  overflow: visible;
  text-overflow: clip;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--border-2);
  border-radius: 999px;
  background: var(--surface);
  cursor: pointer;
  font-family: inherit;
  transition: border-color 0.12s, background 0.12s, box-shadow 0.12s;
}

.chip:hover {
  border-color: var(--primary);
  background: var(--primary-50);
  box-shadow: 0 1px 2px rgba(30, 64, 175, 0.08);
}

.chip:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 1px;
}

.chip-code {
  font-size: 12px;
  font-weight: 500;
  color: var(--text);
  line-height: 1;
}

.chip-n {
  font-size: 11px;
  color: var(--text-3);
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.chip-n.zero {
  color: var(--text-3);
  opacity: 0.65;
}

.row-act {
  text-align: right;
}

.detail-row td {
  padding: 0 12px 10px 12px;
  background: #fbfcfe;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin-left: 22px;
  padding-left: 10px;
  border-left: 2px solid var(--border);
}

.mod-line {
  display: grid;
  grid-template-columns: minmax(180px, 340px) 130px 160px 110px auto;
  gap: 12px;
  align-items: center;
  padding: 5px 8px;
  border-radius: var(--r-sm);
}

.mod-line:hover {
  background: var(--surface-3);
}

.mod-id {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
}

.mod-id:hover .mono {
  color: var(--primary-700);
}

.mod-name {
  font-size: 11.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
}

.mod-meta {
  font-size: 12px;
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.mod-act {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  padding-right: 2px;
}

.mono {
  font-family: var(--mono);
  font-size: 12.5px;
}

.t-strong {
  font-weight: 500;
  color: var(--text);
}

.muted {
  color: var(--text-3);
}

.num {
  font-variant-numeric: tabular-nums;
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
  margin-left: 6px;
  white-space: nowrap;
}

.tag-plain {
  background: var(--surface-3);
  color: var(--text-2);
  margin-left: 0;
}

.tag-shared {
  background: #f5f3ff;
  color: #6d28d9;
  border: 1px solid #e9e4ff;
}

.dialog-note {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.6;
}

.empty-row {
  height: 100px;
  text-align: center;
  color: var(--text-3);
  font-size: 12.5px;
}
</style>
