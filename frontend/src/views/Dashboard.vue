<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h1 class="fc-page-title">工作台</h1>
        <div class="fc-page-subtitle">今天有 {{ riskCount }} 项配置风险需要处理。</div>
      </div>
      <button class="btn" :disabled="loading" @click="load">
        <ProtoIcon name="sync" :size="15" />刷新
      </button>
    </div>

    <div v-if="loading" class="fc-card fc-empty">加载中…</div>

    <template v-else>
      <!-- 摘要：只做计数，不做图表 -->
      <div class="stats">
        <div class="stat red">
          <div class="k"><ProtoIcon name="alert" :size="14" />缺必填变量</div>
          <div class="v">{{ missingKeyCount }}<small>个</small></div>
        </div>
        <div class="stat amber">
          <div class="k"><ProtoIcon name="sync" :size="14" />模板待同步</div>
          <div class="v">{{ todos.templateUpdates.length }}<small>个模块</small></div>
        </div>
        <div class="stat blue">
          <div class="k"><ProtoIcon name="key" :size="14" />Token 7 天内到期</div>
          <div class="v">{{ todos.expiringTokens.length }}<small>个</small></div>
        </div>
        <div class="stat gray">
          <div class="k"><ProtoIcon name="file" :size="14" />24h 高危操作</div>
          <div class="v">{{ todos.criticalAudits.length }}<small>次</small></div>
        </div>
      </div>

      <div v-if="allEmpty" class="fc-card all-ok">
        <el-icon :size="26" color="#047857"><CircleCheck /></el-icon>
        <div>
          <div class="ok-t">一切正常</div>
          <div class="ok-s">当前没有缺必填变量、待同步模板、即将到期 Token 或高危操作。</div>
        </div>
      </div>

      <!-- 四类待办 2×2 -->
      <div class="todo-grid">
        <section class="fc-card todo-card">
          <div class="card-head">
            <div class="card-title">
              <ProtoIcon name="alert" :size="15" style="color: var(--danger)" />
              缺必填变量
              <span class="n">{{ todos.missingRequired.length }}</span>
            </div>
            <button
              v-if="todos.missingRequired.length > 3"
              class="ghost-btn"
              @click="toggle('missing')"
            >
              {{ expanded.missing ? '收起' : '查看全部' }}
            </button>
          </div>
          <div class="card-body" style="padding: 6px 8px">
            <button
              v-for="item in missingShown"
              :key="item.moduleId + '-' + item.environmentId"
              class="todo"
              @click="openMissing(item)"
            >
              <span class="todo-ic red"><ProtoIcon name="alert" :size="14" /></span>
              <span class="todo-main">
                <span class="todo-t">
                  <span :class="item.moduleCode ? 'mono' : ''">{{ item.moduleCode || item.moduleName }}</span>
                  <span class="tag tag-danger">{{ item.environmentName }}</span>
                </span>
                <span class="todo-s">缺 {{ item.missingKeys.join('、') }}</span>
              </span>
              <span class="todo-act">去填写 →</span>
            </button>
            <div v-if="!todos.missingRequired.length" class="todo-empty">
              所有必填变量都已配置
            </div>
          </div>
        </section>

        <section class="fc-card todo-card">
          <div class="card-head">
            <div class="card-title">
              <ProtoIcon name="sync" :size="15" style="color: #d97706" />
              模板待同步
              <span class="n">{{ todos.templateUpdates.length }}</span>
            </div>
            <button
              v-if="todos.templateUpdates.length > 3"
              class="ghost-btn"
              @click="toggle('templates')"
            >
              {{ expanded.templates ? '收起' : '查看全部' }}
            </button>
          </div>
          <div class="card-body" style="padding: 6px 8px">
            <button
              v-for="item in templatesShown"
              :key="item.moduleId"
              class="todo"
              @click="router.push(`/modules/${item.moduleId}`)"
            >
              <span class="todo-ic amber"><ProtoIcon name="layers" :size="14" /></span>
              <span class="todo-main">
                <span class="todo-t">
                  <span>{{ shortName(item.moduleName) }}</span>
                </span>
                <span class="todo-s">模板有新增或定义变更，尚未同步</span>
              </span>
              <span class="todo-act">看 diff →</span>
            </button>
            <div v-if="!todos.templateUpdates.length" class="todo-empty">
              所有模块都已同步到最新模板
            </div>
          </div>
        </section>

        <section class="fc-card todo-card">
          <div class="card-head">
            <div class="card-title">
              <ProtoIcon name="key" :size="15" style="color: var(--primary)" />
              Token 7 天内到期
              <span class="n">{{ todos.expiringTokens.length }}</span>
            </div>
            <button class="ghost-btn" @click="router.push('/tokens')">去续期</button>
          </div>
          <div class="card-body" style="padding: 6px 8px">
            <button
              v-for="item in tokensShown"
              :key="item.id"
              class="todo"
              @click="router.push('/tokens')"
            >
              <span class="todo-ic blue"><ProtoIcon name="key" :size="14" /></span>
              <span class="todo-main">
                <span class="todo-t">
                  <span>{{ item.name }}</span>
                  <span class="tag tag-warn">{{ daysLeft(item.expiresAt) }}</span>
                </span>
                <span class="todo-s">
                  前缀 {{ item.tokenPrefix }} · {{ fmt(item.expiresAt) }} 到期
                </span>
              </span>
              <span class="todo-act">续期 →</span>
            </button>
            <div v-if="!todos.expiringTokens.length" class="todo-empty">
              未来 7 天没有 Token 到期
            </div>
          </div>
        </section>

        <section class="fc-card todo-card">
          <div class="card-head">
            <div class="card-title">
              <ProtoIcon name="file" :size="15" style="color: var(--danger)" />
              24h 高危操作
              <span class="n">{{ todos.criticalAudits.length }}</span>
            </div>
            <button class="ghost-btn" @click="router.push('/audit')">查审计</button>
          </div>
          <div class="card-body" style="padding: 6px 8px">
            <button
              v-for="item in auditsShown"
              :key="item.id"
              class="todo"
              @click="router.push('/audit')"
            >
              <span class="todo-ic red"><ProtoIcon name="eye" :size="14" /></span>
              <span class="todo-main">
                <span class="todo-t">
                  <span>{{ actionText(item.action) }}</span>
                </span>
                <span class="todo-s">{{ item.userEmail || '未知用户' }} · {{ fmt(item.createdAt) }}</span>
              </span>
              <span class="todo-act">查看 →</span>
            </button>
            <div v-if="!todos.criticalAudits.length" class="todo-empty">
              最近 24 小时没有高危操作
            </div>
          </div>
        </section>
      </div>

      <!-- 最近打开的模块 -->
      <section class="recent">
        <div class="card-head">
          <div class="card-title">最近打开的模块</div>
          <button class="btn btn-ghost btn-sm" @click="router.push('/applications')">
            全部模块
          </button>
        </div>
        <table class="recent-table">
          <thead>
            <tr>
              <th>模块</th>
              <th>所属应用</th>
              <th style="width: 90px">变量</th>
              <th style="width: 260px">环境</th>
              <th style="width: 180px">最后修改</th>
              <th style="width: 40px"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="m in todos.recentModules"
              :key="m.id"
              class="clickable"
              @click="router.push(`/modules/${m.id}`)"
            >
              <td>
                <span class="mono t-strong">{{ m.code }}</span>
                <span v-if="m.shared" class="tag tag-shared">共享</span>
              </td>
              <td>
                <span v-if="m.applicationCode">{{ m.applicationCode }}</span>
                <span v-else class="muted">共享模块库</span>
              </td>
              <td class="num">{{ m.variableCount }}</td>
              <td>
                <span
                  v-for="c in m.environments || []"
                  :key="c"
                  class="tag tag-plain"
                  style="margin-right: 4px"
                >
                  {{ c }}
                </span>
                <span v-if="!(m.environments || []).length" class="muted">—</span>
              </td>
              <td class="muted">{{ relative(m.updatedAt) }}</td>
              <td style="text-align: right">
                <ProtoIcon name="arrow-r" :size="15" style="color: #c9cdd6" />
              </td>
            </tr>
            <tr v-if="!todos.recentModules.length">
              <td colspan="6" class="muted" style="text-align: center; padding: 26px 0">
                还没有模块。先到应用看板创建一个。
              </td>
            </tr>
          </tbody>
        </table>
      </section>

    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  BellFilled,
  CircleCheck,
  View,
  Key,
  Refresh,
  RefreshRight,
  WarningFilled
} from '@element-plus/icons-vue'
import { api } from '@/api/http'
import ProtoIcon from '@/components/ProtoIcon.vue'
import type { Id } from '@/types/id'

interface MissingItem {
  moduleId: number
  moduleCode?: string
  moduleName: string
  environmentId: number
  environmentCode?: string
  environmentName: string
  missingKeys: string[]
}
interface Todos {
  missingRequired: MissingItem[]
  templateUpdates: Array<{ moduleId: number; moduleName: string; templateId: number }>
  expiringTokens: Array<{
    id: Id
    name: string
    tokenPrefix: string
    expiresAt: string
  }>
  criticalAudits: Array<{
    id: Id
    action: string
    userEmail?: string
    createdAt: string
  }>
  recentModules: Array<{
    id: Id
    code: string
    name: string
    shared: boolean
    applicationCode?: string
    applicationName?: string
    variableCount: number
    environmentCount: number
    environments?: string[]
    updatedAt: string
  }>
}

const router = useRouter()
const loading = ref(true)
const todos = ref<Todos>({
  missingRequired: [],
  templateUpdates: [],
  expiringTokens: [],
  criticalAudits: [],
  recentModules: []
})
const expanded = reactive({ missing: false, templates: false, tokens: false, audits: false })

const missingKeyCount = computed(() =>
  todos.value.missingRequired.reduce((n, i) => n + i.missingKeys.length, 0)
)

const riskCount = computed(
  () =>
    missingKeyCount.value +
    todos.value.templateUpdates.length +
    todos.value.expiringTokens.length
)

const allEmpty = computed(
  () =>
    !todos.value.missingRequired.length &&
    !todos.value.templateUpdates.length &&
    !todos.value.expiringTokens.length &&
    !todos.value.criticalAudits.length
)

const limit = 3

const missingShown = computed(() =>
  expanded.missing ? todos.value.missingRequired : todos.value.missingRequired.slice(0, limit)
)
const templatesShown = computed(() =>
  expanded.templates ? todos.value.templateUpdates : todos.value.templateUpdates.slice(0, limit)
)
const tokensShown = computed(() =>
  expanded.tokens ? todos.value.expiringTokens : todos.value.expiringTokens.slice(0, limit)
)
const auditsShown = computed(() =>
  expanded.audits ? todos.value.criticalAudits : todos.value.criticalAudits.slice(0, limit)
)

function toggle(key: 'missing' | 'templates') {
  expanded[key] = !expanded[key]
}

function shortName(name: string) {
  return name.replace(/\s*\([^)]*\)\s*$/, '')
}

function openMissing(item: MissingItem) {
  const params = new URLSearchParams()
  if (item.environmentCode) params.set('env', item.environmentCode)
  if (item.missingKeys[0]) params.set('var', item.missingKeys[0])
  const q = params.toString()
  router.push(`/modules/${item.moduleId}${q ? '?' + q : ''}`)
}

function fmt(v?: string) {
  if (!v) return '—'
  return new Date(v).toLocaleString('zh-CN', { hour12: false })
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

function daysLeft(v: string) {
  const ms = new Date(v).getTime() - Date.now()
  const days = Math.max(0, Math.ceil(ms / 86400000))
  return days <= 1 ? '明天到期' : `${days} 天后`
}

const ACTION_TEXT: Record<string, string> = {
  VARIABLE_REVEAL: '查看加密变量明文',
  BACKUP_RESTORE: '恢复备份',
  FILE_DELETE: '删除文件',
  APP_DELETE: '删除应用',
  TOKEN_REVOKE: '吊销 Token',
  BACKUP_OVERWRITE: '覆盖式恢复'
}

function actionText(action: string) {
  return ACTION_TEXT[action] || action
}

async function load() {
  loading.value = true
  try {
    const data = await api.get<Todos>('/api/console/dashboard/todos')
    todos.value = {
      missingRequired: data.missingRequired || [],
      templateUpdates: data.templateUpdates || [],
      expiringTokens: data.expiringTokens || [],
      criticalAudits: data.criticalAudits || [],
      recentModules: data.recentModules || []
    }
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat {
  position: relative;
  overflow: hidden;
  padding: 14px 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
}

.stat::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--line, #e6e8ed);
}

.stat.red {
  --line: var(--danger);
}
.stat.amber {
  --line: #f59e0b;
}
.stat.blue {
  --line: var(--primary);
}
.stat.gray {
  --line: #c3c9d4;
}

.stat .k {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--text-2);
}

.stat .v {
  margin-top: 6px;
  font-size: 26px;
  font-weight: 650;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}

.stat.red .v {
  color: var(--danger);
}
.stat.amber .v {
  color: var(--warn);
}
.stat.blue .v {
  color: var(--primary-600);
}

.stat .v small {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-3);
}

.stat .s {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--text-3);
}

.all-ok {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 16px;
}

.ok-t {
  font-size: 14px;
  font-weight: 600;
  color: var(--ok);
}

.ok-s {
  margin-top: 2px;
  font-size: 12.5px;
  color: var(--text-3);
}

.todo-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.todo-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--border);
}

.card-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13.5px;
  font-weight: 600;
}

.card-title .n {
  padding: 0 6px;
  border-radius: 5px;
  background: var(--surface-3);
  color: var(--text-3);
  font-size: 11.5px;
  font-weight: 600;
  line-height: 17px;
}

.ic {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  display: grid;
  place-items: center;
}

.ic.red {
  background: var(--danger-50);
  color: var(--danger);
}
.ic.amber {
  background: var(--warn-50);
  color: var(--warn);
}
.ic.blue {
  background: var(--primary-50);
  color: var(--primary);
}

.head-note {
  font-size: 11.5px;
  color: var(--text-3);
}

.ghost-btn {
  border: 0;
  background: none;
  padding: 3px 6px;
  border-radius: 5px;
  font-size: 12px;
  color: var(--text-2);
  cursor: pointer;
}

.ghost-btn:hover {
  background: var(--surface-3);
  color: var(--text);
}

.card-body {
  padding: 6px;
  flex: 1;
}

.todo {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 11px 14px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  text-align: left;
  cursor: pointer;
  transition: background 0.14s;
}

.todo + .todo {
  border-top: 1px solid #f2f3f6;
}

.todo:hover {
  background: var(--surface-3);
}

.todo-ic {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border-radius: 7px;
  display: grid;
  place-items: center;
}

.todo-ic.red {
  background: var(--danger-50);
  color: var(--danger);
}
.todo-ic.amber {
  background: var(--warn-50);
  color: var(--warn);
}
.todo-ic.blue {
  background: var(--primary-50);
  color: var(--primary);
}

.todo-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.todo-t {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
}

.todo-t .mono {
  font-size: 12.5px;
}

.todo-s {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-act {
  flex: 0 0 auto;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--primary);
  opacity: 0;
  transition: opacity 0.14s;
  white-space: nowrap;
}

.todo:hover .todo-act {
  opacity: 1;
}

.todo-empty {
  padding: 22px 12px;
  text-align: center;
  font-size: 12px;
  color: var(--text-3);
}

.recent {
  margin-top: 16px;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
}

.recent-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
}

.recent-table th {
  height: 36px;
  padding: 0 12px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
  text-align: left;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-2);
  white-space: nowrap;
}

.recent-table td {
  height: 44px;
  padding: 0 12px;
  border-bottom: 1px solid #f0f1f5;
  font-size: 13px;
  vertical-align: middle;
}

.recent-table tbody tr:last-child td {
  border-bottom: 0;
}

.recent-table tr.clickable {
  cursor: pointer;
}

.recent-table tr.clickable:hover td {
  background: #fbfcfe;
}

.rt-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.rt-code {
  margin-top: 1px;
  font-size: 11px;
  color: var(--text-3);
}

.rt-arrow {
  color: var(--text-3);
  text-align: right;
}

.recent-table tr:hover .rt-arrow {
  color: var(--primary);
}

.num {
  font-variant-numeric: tabular-nums;
}

.muted {
  color: var(--text-3);
}

@media (max-width: 1180px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .todo-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
