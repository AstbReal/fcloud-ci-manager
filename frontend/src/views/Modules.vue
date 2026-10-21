<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">模块</h2>
        <div class="fc-page-subtitle">
          所有应用与共享模块库中的配置单元；点一行进入变量矩阵。
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
        <input v-model="q" placeholder="搜模块名 / code" />
      </label>

      <el-dropdown trigger="click" @command="setApp">
        <button class="field">
          <span class="muted">应用</span>
          <b>{{ appLabel }}</b>
          <ProtoIcon name="chevron" :size="13" style="color: #8b93a1" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="">全部应用</el-dropdown-item>
            <el-dropdown-item command="__shared">共享模块库</el-dropdown-item>
            <el-dropdown-item v-for="a in apps" :key="a.id" :command="String(a.id)">
              {{ a.code }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-dropdown trigger="click" @command="(v: string) => (groupFilter = v)">
        <button class="field">
          <span class="muted">分组</span>
          <b>{{ groupLabel }}</b>
          <ProtoIcon name="chevron" :size="13" style="color: #8b93a1" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="">全部分组</el-dropdown-item>
            <el-dropdown-item command="__none">未分组</el-dropdown-item>
            <el-dropdown-item v-for="g in groupOptions" :key="g" :command="g">
              {{ g }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <button class="btn btn-ghost btn-sm manage-groups" @click="openGroupDialog">
        <ProtoIcon name="sliders" :size="14" />管理分组
      </button>
    </div>

    <div class="card">
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>模块</th>
              <th style="width: 190px">所属应用</th>
              <th style="width: 110px">分组</th>
              <th style="width: 80px">变量</th>
              <th style="width: 80px">环境</th>
              <th style="width: 150px">更新时间</th>
              <th style="width: 140px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in filtered" :key="m.id" @click="open(m)">
              <td>
                <span class="mono t-strong">{{ m.code }}</span>
                <span v-if="m.isShared" class="tag tag-shared">共享</span>
                <div class="m-name">{{ m.name }}</div>
              </td>
              <td class="mono muted">{{ m.applicationCode || '共享模块库' }}</td>
              <td>
                <span v-if="m.groupName" class="tag tag-plain">{{ m.groupName }}</span>
                <span v-else class="muted">—</span>
              </td>
              <td class="num">{{ m.variableCount }}</td>
              <td class="num">{{ m.environmentCount }}</td>
              <td class="muted">{{ relative(m.updatedAt) }}</td>
              <td @click.stop>
                <button class="btn btn-ghost btn-sm" @click="openEdit(m)">编辑</button>
                <button
                  v-if="isAdmin"
                  class="btn btn-ghost btn-sm danger"
                  @click="remove(m)"
                >
                  删除
                </button>
              </td>
            </tr>
            <tr v-if="!filtered.length">
              <td colspan="7" class="empty-row">
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
      :groups="groupOptions"
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
  variableCount: number
  environmentCount: number
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

const appLabel = computed(() => {
  if (!appFilter.value) return '全部'
  if (appFilter.value === '__shared') return '共享模块库'
  return apps.value.find((a) => String(a.id) === appFilter.value)?.code || '全部'
})

const groupLabel = computed(() =>
  groupFilter.value === '__none' ? '未分组' : groupFilter.value || '全部'
)

const groupOptions = computed(() => {
  const set = new Set<string>()
  for (const m of modules.value) {
    if (m.groupName) set.add(m.groupName)
  }
  return Array.from(set)
})

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
      (m.applicationCode || '').toLowerCase().includes(kw)
    )
  })
)

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
  formAppId.value = undefined
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
  try {
    await load()
    apps.value = await api.get<Array<{ id: Id; code: string; name: string }>>(
      '/api/console/admin/applications'
    )
    envs.value = await envApi.list()
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
  width: 240px;
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

.field b {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
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
  min-width: 750px;
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

tbody tr {
  cursor: pointer;
}

tbody tr:last-child td {
  border-bottom: 0;
}

tbody tr:hover td {
  background: #fbfcfe;
}

.mono {
  font-family: var(--mono);
  font-size: 12.5px;
}

.t-strong {
  font-weight: 500;
  color: var(--text);
}

.m-name {
  margin-top: 1px;
  font-size: 11.5px;
  color: var(--text-3);
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
