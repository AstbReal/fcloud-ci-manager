<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h1 class="fc-page-title">应用看板</h1>
        <div class="fc-page-subtitle">
          模块 chip 上的状态点标出问题；订阅共享模块在卡片的「订阅」组里完成。
        </div>
      </div>
      <div class="header-actions">
        <button v-if="isAdmin" class="btn btn-primary" @click="openCreateApp">
          <ProtoIcon name="plus" :size="15" />新建应用
        </button>
        <button class="btn" :disabled="loading" @click="load()">
          <ProtoIcon name="sync" :size="15" />刷新
        </button>
      </div>
    </div>

    <div class="board">
      <!-- 左栏：共享模块库 + 模板库快捷入口 -->
      <aside class="rail">
        <section class="fc-card rail-card">
          <div class="rail-head">
            <span>共享模块库</span>
            <span class="rail-count">{{ board.sharedModules.length }}</span>
            <el-button
              text
              type="primary"
              size="small"
              class="rail-new"
              @click="openCreateShared"
            >
              新建
            </el-button>
          </div>
          <div class="rail-list">
            <button
              v-for="m in board.sharedModules"
              :key="m.id"
              class="rail-item"
              @click="openSharedDrawer(m)"
            >
              <span class="rail-ic">
                <el-icon :size="13"><Connection /></el-icon>
              </span>
              <span class="rail-main">
                <span class="rail-nm mono">{{ m.code }}</span>
                <span class="rail-cd">{{ m.description || m.name }}</span>
              </span>
              <span class="rail-meta">{{ m.subscriptionCount }} 订阅</span>
            </button>
            <div v-if="!board.sharedModules.length" class="rail-empty">
              还没有共享模块
            </div>
          </div>
          <div class="card-foot rail-foot">
            共享模块 = 多应用订阅同一份，改动实时同步
          </div>
        </section>

        <section v-if="isAdmin" class="card rail-card tpl-card">
          <div class="card-head rail-head">
            <div class="card-title">模板库</div>
          </div>
          <div class="card-body rail-body">
            <div class="rail-desc">
              {{ templateCount }} 个模板可实例化，<br />用于快速生成模块骨架。
            </div>
            <button class="btn btn-sm rail-open" @click="router.push('/templates')">
              打开模板库
            </button>
          </div>
        </section>
      </aside>

      <!-- 右栏：搜索 + 筛选 + 应用卡片墙 -->
      <div class="board-main">
        <div class="board-bar">
          <label class="search">
            <ProtoIcon name="search" :size="14" class="s-ic" />
            <input
              ref="searchRef"
              v-model="q"
              class="s-in"
              type="text"
              aria-label="搜索应用或模块"
              placeholder="搜索应用或模块 · 名称 / code"
              autocomplete="off"
              spellcheck="false"
              @keydown="onSearchKeydown"
            />
            <button v-if="q" class="s-clear" title="清空（Esc）" @click="q = ''">
              <ProtoIcon name="x" :size="12" />
            </button>
            <kbd v-else class="s-kbd" title="按 / 快速聚焦">/</kbd>
          </label>

          <div class="segs">
            <button class="seg" :class="{ on: health === 'all' }" @click="health = 'all'">
              全部<span class="n">{{ board.applications.length }}</span>
            </button>
            <button
              class="seg"
              :class="{ on: health === 'missing' }"
              title="有必填变量缺值的应用（变量 × 环境）"
              @click="health = 'missing'"
            >
              <i class="dot red" />缺必填<span class="n">{{ missingApps }}</span>
            </button>
            <button
              class="seg"
              :class="{ on: health === 'pending' }"
              title="关联模板有未同步变更的应用"
              @click="health = 'pending'"
            >
              <i class="dot amber" />待同步<span class="n">{{ pendingApps }}</span>
            </button>
          </div>

          <span v-if="filtered" class="bar-result">
            {{ cards.length }} / {{ board.applications.length }}
          </span>
          <span class="bar-time">检查于 {{ checkedAt }}</span>
        </div>

        <div v-if="loading" class="fc-card fc-empty">加载中…</div>

        <div v-else-if="!board.applications.length" class="fc-card fc-empty">
          <el-icon :size="44" color="#d1d5db"><Grid /></el-icon>
          <p style="margin-top: 12px">还没有应用</p>
          <el-button v-if="isAdmin" type="primary" style="margin-top: 12px" @click="openCreateApp">
            新建第一个应用
          </el-button>
        </div>

        <div v-else-if="!cards.length" class="fc-card fc-empty search-empty">
          <el-icon :size="40" color="#d1d5db"><Search /></el-icon>
          <p style="margin-top: 12px">
            <template v-if="q.trim()">没有匹配「{{ q.trim() }}」的应用或模块</template>
            <template v-else>当前筛选下没有问题应用</template>
          </p>
          <div class="empty-acts">
            <button class="btn btn-sm" @click="resetFilters">清空搜索与筛选</button>
          </div>
        </div>

        <div v-else class="apps">
          <article v-for="card in visibleCards" :key="card.app.id" class="appcard fc-card">
            <div class="appcard-h">
              <div class="app-ic">{{ card.letter }}</div>
              <div class="app-head-main">
                <div class="app-t">
                  <span>{{ card.app.name }}</span>
                  <HealthDot variant="badge" :health="card.health" />
                </div>
                <div class="app-c mono">{{ card.app.code }}</div>
              </div>
              <el-dropdown
                v-if="isAdmin"
                trigger="click"
                @command="(c: string) => onAppCommand(c, card.app)"
              >
                <button class="app-more" title="更多操作">
                  <el-icon :size="15"><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑应用</el-dropdown-item>
                    <el-dropdown-item command="copy" :disabled="!card.app.privateModules.length">
                      复制模块到其他应用
                    </el-dropdown-item>
                    <el-dropdown-item command="delete" divided style="color: #dc2626">
                      删除应用
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>

            <p v-if="card.app.description" class="app-d">{{ card.app.description }}</p>

            <div class="groups">
              <div v-for="grp in card.groups" :key="grp.name" class="group">
                <div class="group-l">{{ grp.name || '未分组' }}</div>
                <div class="chips">
                  <button
                    v-for="m in grp.modules"
                    :key="m.id"
                    class="chip"
                    :class="{ 'chip-hit': isHit(m) }"
                    :title="`${m.name} · ${m.code}`"
                    @click="goModule(m)"
                  >
                    <HealthDot :health="m.health" />
                    <span class="chip-nm">{{ m.name }}</span>
                  </button>
                </div>
              </div>

              <div class="group">
                <div class="group-l">
                  订阅
                  <span v-if="card.app.subscribedModules.length" class="g-count">
                    {{ card.app.subscribedModules.length }}
                  </span>
                </div>
                <div class="chips">
                  <button
                    v-for="m in card.app.subscribedModules"
                    :key="'s' + m.id"
                    class="chip chip-sub"
                    :class="{ 'chip-hit': isHit(m) }"
                    :title="`${m.name} · ${m.code}`"
                    @click="goModule(m)"
                  >
                    <HealthDot :health="m.health" />
                    <span class="chip-nm">{{ m.name }}</span>
                  </button>
                  <button
                    class="chip chip-add"
                    :class="{ on: pickerOpen && pickerApp?.id === card.app.id }"
                    :data-sub-anchor="card.app.id"
                    title="从共享模块库引用一份配置：源模块改动会实时同步到本应用"
                    @click.stop="openPicker(card.app, $event)"
                  >
                    <el-icon :size="12"><Plus /></el-icon>
                    订阅共享模块
                  </button>
                </div>
              </div>
            </div>

            <div class="appcard-f">
              <span><b>{{ card.app.privateModules.length }}</b> 私有模块</span>
              <span><b>{{ card.app.subscribedModules.length }}</b> 订阅</span>
              <button
                class="foot-add"
                title="在当前应用下新建私有模块，只有本应用可见"
                @click="openCreateModule(card.app)"
              >
                + 私有模块
              </button>
            </div>
          </article>
        </div>

        <div v-if="hiddenCount" class="more-row">
          <button class="btn" @click="showMore">
            加载更多<span class="more-n">还有 {{ hiddenCount }} 个</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 正向入口：应用卡片 → + 订阅 -->
    <SubscribePicker
      v-model="pickerOpen"
      :app="pickerApp"
      :modules="board.sharedModules"
      :anchor="pickerAnchor"
      @toggle="onPickerToggle"
    />

    <!-- 反向入口：共享模块 → 订阅应用 -->
    <SubscribeAppsDrawer
      v-model="drawerOpen"
      :module="drawerModule"
      :apps="board.applications"
      :busy="drawerBusy"
      @toggle="onDrawerToggle"
      @subscribe-all="onDrawerSubscribeAll"
      @unsubscribe-all="onDrawerUnsubscribeAll"
      @edit="onEditSharedFromDrawer"
      @remove="onDeleteSharedFromDrawer"
      @open="onOpenSharedMatrix"
    />

    <!-- 应用编辑 / 新建（手动 or 从 GitLab 导入） -->
    <el-drawer
      v-model="appDrawer"
      :title="appForm.id ? '编辑应用' : '新建应用'"
      size="480px"
    >
      <div class="app-form">
        <div v-if="!appForm.id" class="fld">
          <span class="fld-lb">创建方式</span>
          <div class="kind-switch">
            <button
              type="button"
              class="kind-opt"
              :class="{ on: appForm.source === 'manual' }"
              @click="setSource('manual')"
            >
              <span class="k-t">手动填写</span>
              <span class="k-s">直接填 code 与名称</span>
            </button>
            <button
              type="button"
              class="kind-opt"
              :class="{ on: appForm.source === 'gitlab' }"
              @click="setSource('gitlab')"
            >
              <span class="k-t">从 GitLab 导入</span>
              <span class="k-s">选仓库，自动带出信息</span>
            </button>
          </div>
        </div>

        <div v-if="appForm.source === 'gitlab' && !appForm.id" class="fld">
          <span class="fld-lb">
            GitLab 项目<span class="req">*</span>
            <span class="fld-opt">只列出你有权限的项目</span>
          </span>
          <el-select
            v-model="appForm.projectId"
            filterable
            remote
            clearable
            :remote-method="searchProjects"
            :loading="glLoading"
            placeholder="搜索项目名或路径"
            style="width: 100%"
            @change="onPickProject"
          >
            <el-option
              v-for="p in glProjects"
              :key="p.id"
              :label="`${p.name} · ${p.pathWithNamespace}`"
              :value="p.id"
            />
          </el-select>
          <div v-if="glError" class="gl-err">
            <ProtoIcon name="alert" :size="13" />
            <span>{{ glError }}</span>
            <router-link class="gl-link" to="/settings">去系统管理配置</router-link>
          </div>
          <span v-else class="fld-hint">
            导入后会记录项目 id 与仓库地址；同一个项目重复导入会被拒绝。
            项目名是中文时需手动补一个 code。
          </span>
        </div>

        <label class="fld">
          <span class="fld-lb">
            Code<span class="req">*</span>
            <span v-if="appForm.id" class="fld-opt">创建后不可修改</span>
          </span>
          <el-input
            v-model="appForm.code"
            :disabled="!!appForm.id"
            class="k-input"
            placeholder="payment-service"
            @blur="appForm.code = appForm.code.trim()"
          />
        </label>

        <label class="fld">
          <span class="fld-lb">名称<span class="req">*</span></span>
          <el-input v-model="appForm.name" placeholder="支付服务" />
        </label>

        <label class="fld">
          <span class="fld-lb">描述<span class="fld-opt">选填</span></span>
          <el-input
            v-model="appForm.description"
            type="textarea"
            :rows="3"
            placeholder="这个应用负责什么，谁来维护"
          />
        </label>

        <label class="fld">
          <span class="fld-lb">
            仓库地址
            <span class="fld-opt">
              {{ appForm.gitlabProjectId ? `GitLab 项目 #${appForm.gitlabProjectId}` : '选填' }}
            </span>
          </span>
          <el-input
            v-model="appForm.repoUrl"
            placeholder="https://gitlab.example.com/group/payment-service"
            @blur="appForm.repoUrl = (appForm.repoUrl || '').trim()"
          />
          <span class="fld-hint">
            用于从应用跳回代码。项目在 GitLab 改名或迁移后不会自动同步，需要在这里手动改；留空表示不记录。
          </span>
        </label>
      </div>

      <template #footer>
        <div class="drawer-foot">
          <span class="foot-note">
            {{
              appForm.id
                ? '保存后立即生效'
                : appForm.source === 'gitlab'
                  ? '将创建应用并关联该仓库'
                  : '创建后可在卡片里加模块'
            }}
          </span>
          <div class="foot-actions">
            <button class="btn" @click="appDrawer = false">取消</button>
            <button class="btn btn-primary" :disabled="saving" @click="saveApp">
              {{ saving ? '保存中…' : appForm.source === 'gitlab' && !appForm.id ? '导入' : '保存' }}
            </button>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 共享模块新建 -->
    <!-- 共享模块：新建 / 编辑 -->
    <ModuleFormDrawer
      v-model="sharedDrawer"
      kind="shared"
      :module="editingShared"
      :environments="environments"
      :apps="board.applications"
      :groups="moduleGroups"
      lock-kind
      @saved="load()"
    />

    <!-- 私有模块：新建 / 编辑 -->
    <ModuleFormDrawer
      v-model="moduleDrawer"
      kind="private"
      :app-id="moduleAppId"
      :module="editingModule"
      :environments="environments"
      :apps="board.applications"
      lock-kind
      @saved="load()"
    />

    <!-- 复制模块（替代原跨卡片拖拽） -->
    <el-dialog v-model="copyDialog" title="复制模块到其他应用" width="460px">
      <el-form :model="copyForm" label-position="top">
        <el-form-item label="源模块" required>
          <el-select v-model="copyForm.sourceId" style="width: 100%">
            <el-option
              v-for="m in copySourceApp?.privateModules || []"
              :key="m.id"
              :label="`${m.name} (${m.code})`"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标应用" required>
          <el-select v-model="copyForm.targetAppId" style="width: 100%">
            <el-option
              v-for="a in copyTargetApps"
              :key="a.id"
              :label="`${a.name} (${a.code})`"
              :value="a.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="复制内容">
          <el-radio-group v-model="copyForm.withValues">
            <el-radio :value="false">仅骨架（只复制变量定义）</el-radio>
            <el-radio :value="true">连同值（含全局值与覆盖）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCopy">复制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, MoreFilled } from '@element-plus/icons-vue'
import { api } from '@/api/http'
import ProtoIcon from '@/components/ProtoIcon.vue'
import { useUserStore } from '@/stores/user'
import HealthDot from '@/components/HealthDot.vue'
import SubscribePicker from '@/components/SubscribePicker.vue'
import SubscribeAppsDrawer from '@/components/SubscribeAppsDrawer.vue'
import ModuleFormDrawer from '@/components/ModuleFormDrawer.vue'
import type { BoardApp, BoardData, MiniModule, ModuleHealth, SharedCard } from '@/types/board'
import type { Id } from '@/types/id'

interface Env {
  id: Id
  code: string
  name: string
  sortOrder: number
  parentEnvId?: Id | null
}

const router = useRouter()
const store = useUserStore()
const isAdmin = computed(() => store.isSystemAdmin())

const loading = ref(true)
const saving = ref(false)
const board = ref<BoardData>({ applications: [], sharedModules: [] })
const environments = ref<Env[]>([])
const templateCount = ref(0)
const checkedAt = ref('—')

/** 首屏卡片数量：应用多时一次性铺满 DOM 是卡顿的主因，先渲染一批 */
const PAGE_SIZE = 24
type HealthFilter = 'all' | 'missing' | 'pending'

const q = ref('')
const health = ref<HealthFilter>('all')
const renderLimit = ref(PAGE_SIZE)
const searchRef = ref<HTMLInputElement | null>(null)

const appDrawer = ref(false)
const sharedDrawer = ref(false)
const moduleDrawer = ref(false)
const copyDialog = ref(false)

const pickerOpen = ref(false)
const pickerApp = ref<BoardApp | null>(null)
const pickerAnchor = ref<HTMLElement | null>(null)
const drawerOpen = ref(false)
const drawerModule = ref<SharedCard | null>(null)
const drawerBusy = ref(false)

const appForm = reactive<{
  id?: Id
  source: 'manual' | 'gitlab'
  projectId?: number
  repoUrl?: string
  /** 仅编辑态展示：该应用来自哪个 GitLab 项目（不可改） */
  gitlabProjectId?: Id
  code: string
  name: string
  description: string
}>({
  source: 'manual',
  code: '',
  name: '',
  description: ''
})
const editingShared = ref<SharedCard | null>(null)
const editingModule = ref<MiniModule | null>(null)
const moduleAppId = ref<Id | undefined>()

const copySourceApp = ref<BoardApp | null>(null)
const copyForm = reactive<{ sourceId?: Id; targetAppId?: Id; withValues: boolean }>({
  withValues: false
})

// ---- GitLab 导入 ----
interface GlProject {
  id: number
  name: string
  pathWithNamespace: string
  webUrl: string
  description?: string
  defaultBranch?: string
  lastActivityAt?: string
}

const glLoading = ref(false)
const glProjects = ref<GlProject[]>([])
const glError = ref('')

async function loadProjects(keyword: string) {
  glLoading.value = true
  try {
    const kw = (keyword || '').trim()
    // 用 fetch 而不是 api.get：配置缺失是预期情况，走内联提示而不是全局 toast
    const res = await fetch(
      `/api/console/admin/settings/gitlab/projects${kw ? `?search=${encodeURIComponent(kw)}` : ''}`,
      { credentials: 'same-origin' }
    )
    const body = await res.json()
    if (body.code !== 0) {
      glProjects.value = []
      glError.value = body.message || '拉取 GitLab 项目失败'
      return
    }
    glError.value = ''
    glProjects.value = (body.data || []) as GlProject[]
  } catch {
    glProjects.value = []
    glError.value = '拉取项目失败，请检查网络'
  } finally {
    glLoading.value = false
  }
}

function searchProjects(keyword: string) {
  void loadProjects(keyword)
}

const moduleGroups = computed(() => {
  const set = new Set<string>()
  for (const app of board.value.applications) {
    for (const m of app.privateModules || []) {
      if (m.groupName) set.add(m.groupName)
    }
  }
  return Array.from(set)
})

const copyTargetApps = computed(() =>
  board.value.applications.filter((a) => a.id !== copySourceApp.value?.id)
)

function letterOf(app: BoardApp) {
  return (app.name || app.code || '?').charAt(0).toUpperCase()
}

function groupsOf(app: BoardApp) {
  const map = new Map<string, MiniModule[]>()
  for (const m of app.privateModules || []) {
    const g = m.groupName || ''
    if (!map.has(g)) map.set(g, [])
    map.get(g)!.push(m)
  }
  return Array.from(map.entries()).map(([name, modules]) => ({ name, modules }))
}

function cardHealth(app: BoardApp): ModuleHealth {
  const all = [...(app.privateModules || []), ...(app.subscribedModules || [])]
  return {
    missingRequired: all.reduce((n, m) => n + (m.health?.missingRequired || 0), 0),
    templatePending: all.some((m) => !!m.health?.templatePending)
  }
}

interface AppCardVm {
  app: BoardApp
  letter: string
  groups: Array<{ name: string; modules: MiniModule[] }>
  health: ModuleHealth
}

/**
 * 分组与健康度只在看板数据变化时算一次。
 * 原先在模板里直接调 groupsOf()/cardHealth()，每次重渲染（包括输入搜索的每个
 * 按键）都要把所有应用的模块重新聚合一遍，应用一多就明显掉帧。
 */
const allCards = computed<AppCardVm[]>(() =>
  board.value.applications.map((app) => ({
    app,
    letter: letterOf(app),
    groups: groupsOf(app),
    health: cardHealth(app)
  }))
)

const needle = computed(() => q.value.trim().toLowerCase())

/** 搜应用名 / code / 描述，也搜它下面的模块名与 code —— 否则「找这个模块在哪个应用」只能靠翻 */
function matches(card: AppCardVm, n: string) {
  const { app } = card
  if (
    app.name?.toLowerCase().includes(n) ||
    app.code?.toLowerCase().includes(n) ||
    app.description?.toLowerCase().includes(n)
  ) {
    return true
  }
  return [...(app.privateModules || []), ...(app.subscribedModules || [])].some(
    (m) => m.name?.toLowerCase().includes(n) || m.code?.toLowerCase().includes(n)
  )
}

const cards = computed<AppCardVm[]>(() => {
  const n = needle.value
  return allCards.value.filter((card) => {
    if (health.value === 'missing' && card.health.missingRequired === 0) return false
    if (health.value === 'pending' && !card.health.templatePending) return false
    return !n || matches(card, n)
  })
})

const visibleCards = computed(() => cards.value.slice(0, renderLimit.value))
const hiddenCount = computed(() => Math.max(0, cards.value.length - visibleCards.value.length))
const filtered = computed(() => !!needle.value || health.value !== 'all')

const missingApps = computed(
  () => allCards.value.filter((c) => c.health.missingRequired > 0).length
)
const pendingApps = computed(
  () => allCards.value.filter((c) => c.health.templatePending).length
)

/** 命中的模块 chip 加一层描边：搜模块时能一眼看到是哪个 */
const hitModuleIds = computed(() => {
  const set = new Set<string>()
  const n = needle.value
  if (!n) return set
  for (const card of allCards.value) {
    for (const m of [...(card.app.privateModules || []), ...(card.app.subscribedModules || [])]) {
      if (m.name?.toLowerCase().includes(n) || m.code?.toLowerCase().includes(n)) {
        set.add(String(m.id))
      }
    }
  }
  return set
})

function isHit(m: MiniModule) {
  return hitModuleIds.value.has(String(m.id))
}

watch([needle, health], () => {
  renderLimit.value = PAGE_SIZE
})

function showMore() {
  renderLimit.value += PAGE_SIZE
}

function resetFilters() {
  q.value = ''
  health.value = 'all'
}

function onSearchKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  e.preventDefault()
  if (q.value) q.value = ''
  else (e.target as HTMLInputElement).blur()
}

function isTypingTarget(el: EventTarget | null) {
  const node = el as HTMLElement | null
  if (!node) return false
  return (
    node.tagName === 'INPUT' ||
    node.tagName === 'TEXTAREA' ||
    node.tagName === 'SELECT' ||
    node.isContentEditable
  )
}

/** 按 / 聚焦搜索：列表型页面里键盘比鼠标快 */
function onGlobalKeydown(e: KeyboardEvent) {
  if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey) return
  if (isTypingTarget(e.target)) return
  e.preventDefault()
  searchRef.value?.focus()
  searchRef.value?.select()
}

function goModule(m: MiniModule) {
  router.push(`/modules/${m.id}`)
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const [b, envs, tpls] = await Promise.all([
      api.get<BoardData>('/api/console/applications/board'),
      api.get<Env[]>('/api/console/admin/environments').catch(() => [] as Env[]),
      api.get<unknown[]>('/api/console/admin/templates').catch(() => [])
    ])
    board.value = b
    templateCount.value = tpls.length
    if (!silent) {
      environments.value = envs
      checkedAt.value = new Date().toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit'
      })
    }
    // 静默刷新后把弹层指向新对象与新的锚点，避免订阅状态/位置丢失
    if (pickerApp.value) {
      pickerApp.value =
        b.applications.find((a) => a.id === pickerApp.value!.id) || null
    }
    if (drawerModule.value) {
      drawerModule.value =
        b.sharedModules.find((m) => m.id === drawerModule.value!.id) || drawerModule.value
    }
    if (silent && pickerApp.value) {
      await nextTick()
      const anchor = document.querySelector<HTMLElement>(
        `[data-sub-anchor="${pickerApp.value.id}"]`
      )
      if (anchor) pickerAnchor.value = anchor
    }
  } finally {
    if (!silent) loading.value = false
  }
}

// ---------------- 订阅 ----------------

function openPicker(app: BoardApp, e: MouseEvent) {
  const anchor = e.currentTarget as HTMLElement
  if (pickerOpen.value && pickerApp.value?.id === app.id) {
    pickerOpen.value = false
    return
  }
  pickerApp.value = app
  pickerAnchor.value = anchor
  pickerOpen.value = true
}

function openSharedDrawer(m: SharedCard) {
  drawerModule.value = m
  drawerOpen.value = true
}

/** 本地追加一条订阅引用；健康度由后端按"该应用 + 该模块"重新聚合，前端先占位 */
function appendSubscription(app: BoardApp, shared: SharedCard) {
  app.subscribedModules.push({
    id: shared.id,
    code: shared.code,
    name: shared.name,
    health: { missingRequired: 0, templatePending: false }
  })
  shared.subscriptionCount += 1
}

async function toggleSub(app: BoardApp, moduleId: Id) {
  const shared = board.value.sharedModules.find((m) => m.id === moduleId)
  const idx = app.subscribedModules.findIndex((m) => m.id === moduleId)
  if (idx >= 0) {
    await api.del(`/api/console/admin/applications/${app.id}/subscriptions/${moduleId}`)
    app.subscribedModules.splice(idx, 1)
    if (shared) shared.subscriptionCount = Math.max(0, shared.subscriptionCount - 1)
    ElMessage.success(`已取消订阅 ${shared?.name || ''}`)
    return
  }
  if (!shared) return
  await api.post(`/api/console/admin/applications/${app.id}/subscriptions`, { moduleId })
  appendSubscription(app, shared)
  ElMessage.success(`已订阅 ${shared.name}`)
  void load(true)
}

function onPickerToggle(moduleId: Id) {
  if (pickerApp.value) void toggleSub(pickerApp.value, moduleId)
}

function onDrawerToggle(appId: Id) {
  const app = board.value.applications.find((a) => a.id === appId)
  if (app && drawerModule.value) void toggleSub(app, drawerModule.value.id)
}

/** 批量订阅/批量取消的统一返回 */
interface BatchResult {
  requested: number
  changed: number
  skipped: number
}

/**
 * 批量订阅。作用范围由抽屉传进来（当前列表里可见的应用），
 * 搜索状态下只订阅匹配项，不会偷偷把整批都订上。
 *
 * 一次请求提交整批 appIds：以前是 for 循环逐个 await，60 个应用就是 60 次串行往返。
 */
async function onDrawerSubscribeAll(appIds: Id[]) {
  const shared = drawerModule.value
  if (!shared || drawerBusy.value) return
  const idSet = new Set(appIds.map(String))
  const pending = board.value.applications.filter(
    (a) =>
      idSet.has(String(a.id)) &&
      !(a.subscribedModules || []).some((m) => m.id === shared.id)
  )
  if (!pending.length) return

  drawerBusy.value = true
  let ok = false
  try {
    const r = await api.post<BatchResult>(
      `/api/console/admin/modules/${shared.id}/subscriptions`,
      { appIds: pending.map((a) => a.id) }
    )
    // 服务端已确认，本地先补齐，不用等看板重新加载就能看到勾选状态
    for (const app of pending) {
      if (!(app.subscribedModules || []).some((m) => m.id === shared.id)) {
        appendSubscription(app, shared)
      }
    }
    ok = true
    if (r.changed) {
      ElMessage.success(`已为 ${r.changed} 个应用订阅「${shared.name}」`)
    } else {
      ElMessage.info(`这 ${r.requested} 个应用已订阅「${shared.name}」`)
    }
  } catch {
    // 具体错误由拦截器统一提示，这里不再叠一条
  } finally {
    drawerBusy.value = false
  }
  // 健康度与订阅计数以后端为准
  if (ok) void load(true)
}

/**
 * 批量取消订阅：会一次性让多个应用失去该模块的引用，属于破坏性操作，
 * 所以先确认再执行，并在确认框里写清「已配置的值不会删除」。
 */
async function onDrawerUnsubscribeAll(appIds: Id[]) {
  const shared = drawerModule.value
  if (!shared || drawerBusy.value) return
  const idSet = new Set(appIds.map(String))
  const targets = board.value.applications.filter(
    (a) =>
      idSet.has(String(a.id)) &&
      (a.subscribedModules || []).some((m) => m.id === shared.id)
  )
  if (!targets.length) return

  try {
    await ElMessageBox.confirm(
      `将取消 ${targets.length} 个应用对「${shared.name}」的订阅。` +
        '这些应用的流水线将不再能引用该模块的变量；已配置的覆盖值不会被删除，重新订阅后仍在。',
      '批量取消订阅',
      {
        type: 'warning',
        confirmButtonText: `取消 ${targets.length} 个订阅`,
        confirmButtonClass: 'el-button--danger',
        cancelButtonText: '再想想'
      }
    )
  } catch {
    return
  }

  drawerBusy.value = true
  let ok = false
  try {
    // DELETE 带 body：上百个 id 走 query string 会顶到 URL 长度上限
    const r = await api.del<BatchResult>(
      `/api/console/admin/modules/${shared.id}/subscriptions`,
      { appIds: targets.map((a) => a.id) }
    )
    for (const app of targets) {
      const idx = app.subscribedModules.findIndex((m) => m.id === shared.id)
      if (idx >= 0) app.subscribedModules.splice(idx, 1)
    }
    const remote = board.value.sharedModules.find((m) => m.id === shared.id)
    if (remote) remote.subscriptionCount = Math.max(0, remote.subscriptionCount - r.changed)
    ok = true
    if (r.changed) {
      ElMessage.success(`已取消 ${r.changed} 个应用对「${shared.name}」的订阅`)
    } else {
      ElMessage.info('这些应用本来就没有订阅该模块')
    }
  } catch {
    // 同上，错误提示交给拦截器
  } finally {
    drawerBusy.value = false
  }
  if (ok) void load(true)
}

function onEditSharedFromDrawer() {
  if (drawerModule.value) openEditShared(drawerModule.value)
}

function onDeleteSharedFromDrawer() {
  if (drawerModule.value) void deleteShared(drawerModule.value)
}

function onOpenSharedMatrix() {
  if (!drawerModule.value) return
  const id = drawerModule.value.id
  drawerOpen.value = false
  router.push(`/modules/${id}`)
}

// ---------------- 应用 ----------------

function openCreateApp() {
  appForm.id = undefined
  appForm.source = 'manual'
  appForm.projectId = undefined
  appForm.repoUrl = ''
  appForm.gitlabProjectId = undefined
  appForm.code = ''
  appForm.name = ''
  appForm.description = ''
  appDrawer.value = true
}

function setSource(src: 'manual' | 'gitlab') {
  appForm.source = src
  if (src === 'gitlab' && !glProjects.value.length) {
    void loadProjects('')
  }
}

function slug(raw: string) {
  return raw
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48)
}

function onPickProject(id: number | undefined) {
  const p = glProjects.value.find((x) => x.id === id)
  if (!p) return
  appForm.repoUrl = p.webUrl
  appForm.name = p.name
  if (p.description) appForm.description = p.description
  const guess = slug(p.name) || slug(p.pathWithNamespace.split('/').pop() || '')
  if (guess) appForm.code = guess
}

function onAppCommand(cmd: string, app: BoardApp) {
  if (cmd === 'edit') {
    appForm.id = app.id
    appForm.source = 'manual'
    appForm.projectId = undefined
    appForm.repoUrl = app.repoUrl || ''
    appForm.gitlabProjectId = app.gitlabProjectId
    appForm.code = app.code
    appForm.name = app.name
    appForm.description = app.description || ''
    appDrawer.value = true
  } else if (cmd === 'copy') {
    openCopy(app)
  } else if (cmd === 'delete') {
    void deleteApp(app)
  }
}

async function saveApp() {
  if (!appForm.code || !appForm.name) {
    ElMessage.warning('Code 与名称必填')
    return
  }
  const repoUrl = (appForm.repoUrl || '').trim()
  saving.value = true
  try {
    if (appForm.id) {
      await api.put(`/api/console/admin/applications/${appForm.id}`, {
        code: appForm.code,
        name: appForm.name,
        description: appForm.description,
        // 空串 = 清空仓库地址（后端把空串归一化成 null）
        repoUrl
      })
    } else if (appForm.source === 'gitlab') {
      if (!appForm.projectId) {
        ElMessage.warning('请选择要导入的 GitLab 项目')
        return
      }
      await api.post('/api/console/admin/applications/from-gitlab', {
        projectId: appForm.projectId,
        code: appForm.code,
        name: appForm.name,
        description: appForm.description,
        repoUrl: repoUrl || undefined
      })
    } else {
      await api.post('/api/console/admin/applications', {
        code: appForm.code,
        name: appForm.name,
        description: appForm.description,
        repoUrl: repoUrl || undefined
      })
    }
    ElMessage.success('已保存')
    appDrawer.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function deleteApp(app: BoardApp) {
  try {
    const { value } = await ElMessageBox.prompt(
      `删除应用会级联删除其模块、Token 与订阅。请输入应用 code「${app.code}」确认：`,
      '删除应用',
      { type: 'warning', inputPlaceholder: app.code }
    )
    await api.del(`/api/console/admin/applications/${app.id}`, { confirmName: value })
    ElMessage.success('已删除')
    await load()
  } catch {
    // 用户取消或接口失败（错误已提示）
  }
}

// ---------------- 共享模块 ----------------

function openCreateShared() {
  editingShared.value = null
  sharedDrawer.value = true
}

function openEditShared(m: SharedCard) {
  editingShared.value = m
  sharedDrawer.value = true
}

async function deleteShared(m: SharedCard) {
  if (m.subscriptionCount > 0) {
    ElMessage.warning(
      `「${m.name}」还有 ${m.subscriptionCount} 个订阅方，先取消订阅才能删除`
    )
    return
  }
  try {
    await ElMessageBox.confirm(
      `删除共享模块「${m.name}」会同时删除它的变量与文件，且不可恢复。`,
      '删除共享模块',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
    await api.del(`/api/console/admin/modules/${m.id}`)
    ElMessage.success('已删除')
    drawerOpen.value = false
    await load()
  } catch {
    // 取消或接口报错（已提示）
  }
}

// ---------------- 模块 ----------------

function openCreateModule(app: BoardApp) {
  moduleAppId.value = app.id
  editingModule.value = null
  moduleDrawer.value = true
}

// ---------------- 复制模块 ----------------

function openCopy(app: BoardApp) {
  copySourceApp.value = app
  copyForm.sourceId = app.privateModules[0]?.id
  copyForm.targetAppId = copyTargetApps.value[0]?.id
  copyForm.withValues = false
  copyDialog.value = true
}

async function doCopy() {
  if (!copyForm.sourceId || !copyForm.targetAppId) {
    ElMessage.warning('请选择源模块与目标应用')
    return
  }
  saving.value = true
  try {
    await api.post(`/api/console/admin/modules/${copyForm.sourceId}/copy`, {
      targetAppId: copyForm.targetAppId,
      withValues: copyForm.withValues
    })
    ElMessage.success('已复制')
    copyDialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
  window.addEventListener('keydown', onGlobalKeydown)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKeydown))
</script>

<style scoped>
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.board {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.rail {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.rail-card {
  overflow: hidden;
}


.tpl-card .rail-head {
  padding: 11px 13px;
}

.tpl-card .card-title {
  font-size: 13px;
}

.rail-body {
  padding: 12px 13px;
}

.rail-desc {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
}

.app-form {
  display: flex;
  flex-direction: column;
}

.fld {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 16px;
}

.fld:last-child {
  margin-bottom: 0;
}

.fld-lb {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-2);
}

.fld-opt {
  font-size: 11px;
  font-weight: 400;
  color: var(--text-3);
}

.fld-hint {
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--text-3);
}

.req {
  color: var(--danger);
  font-style: normal;
}

.k-input :deep(.el-input__inner) {
  font-family: var(--mono);
  font-size: 12.5px;
}

.kind-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.kind-opt {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-height: 58px;
  padding: 9px 11px;
  border: 1px solid var(--border-2);
  border-radius: var(--r);
  background: var(--surface);
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  transition: 0.14s;
}

.kind-opt:hover {
  border-color: #b9c0cc;
  background: var(--surface-2);
}

.kind-opt.on {
  border-color: var(--primary);
  background: var(--primary-50);
  box-shadow: inset 0 0 0 1px var(--primary);
}

.k-t {
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
}

.kind-opt.on .k-t {
  color: var(--primary-700);
}

.k-s {
  font-size: 11.5px;
  line-height: 1.45;
  color: var(--text-3);
}

.gl-err {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 9px;
  border-radius: var(--r-sm);
  background: var(--warn-50);
  border: 1px solid #f5e0b0;
  color: var(--warn);
  font-size: 11.5px;
  line-height: 1.5;
}

.gl-link {
  margin-left: auto;
  color: var(--warn);
  font-weight: 500;
  white-space: nowrap;
}

.drawer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.foot-note {
  font-size: 11.5px;
  color: var(--text-3);
}

.foot-actions {
  display: flex;
  gap: 8px;
}

.rail-foot {
  padding: 9px 13px;
  font-size: 11px;
}

.rail-open {
  width: 100%;
  margin-top: 10px;
}

.rail-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
  font-weight: 600;
}

.rail-count {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-3);
  background: var(--surface-3);
  border-radius: 5px;
  padding: 0 6px;
  line-height: 17px;
}

.rail-new {
  margin-left: auto;
}

.rail-list {
  /* 共享模块一多就把左栏撑得老长，给个上限自滚动 */
  max-height: 46vh;
  overflow: auto;
  padding: 6px;
}

.rail-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 8px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  text-align: left;
  cursor: pointer;
  transition: 0.14s;
}

.rail-item:hover {
  background: var(--surface-3);
}

.rail-ic {
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  border-radius: 6px;
  background: #f5f3ff;
  color: var(--purple);
  display: grid;
  place-items: center;
}

.rail-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}

.rail-nm {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-cd {
  font-size: 10.5px;
  color: var(--text-3);
  /* 描述只留一行：左栏是索引，不是阅读区，换行会把列表撑散 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-meta {
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
}

.rail-empty {
  padding: 14px 8px;
  font-size: 12px;
  color: var(--text-3);
}

.rail-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 11px 14px;
  border: 0;
  background: none;
  font-size: 12.5px;
  color: var(--primary);
  cursor: pointer;
}

.rail-link:hover {
  background: var(--surface-2);
}

.board-main {
  min-width: 0;
}

.board-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

/* ---------- 搜索 ---------- */
.search {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 300px;
  max-width: 100%;
  height: 32px;
  padding: 0 8px 0 10px;
  border: 1px solid var(--border-2);
  border-radius: var(--r-sm);
  background: var(--surface);
  transition: 0.14s;
}

.search:focus-within {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.s-ic {
  flex: 0 0 14px;
  color: var(--text-3);
}

.search:focus-within .s-ic {
  color: var(--primary);
}

.s-in {
  flex: 1;
  min-width: 0;
  height: 100%;
  padding: 0;
  border: 0;
  outline: none;
  background: none;
  font-size: 12.5px;
  color: var(--text);
}

.s-in::placeholder {
  color: #98a1b0;
}

.s-clear {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  border: 0;
  border-radius: 50%;
  background: var(--surface-3);
  color: var(--text-3);
  cursor: pointer;
}

.s-clear:hover {
  background: var(--border-2);
  color: var(--text);
}

.s-kbd {
  flex: 0 0 auto;
  min-width: 18px;
  padding: 0 4px;
  border: 1px solid var(--border);
  border-bottom-width: 2px;
  border-radius: 4px;
  background: var(--surface-2);
  color: var(--text-3);
  font: 500 10.5px/15px var(--mono);
  text-align: center;
}

/* ---------- 健康度筛选（兼作图例） ---------- */
.segs {
  display: flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--border-2);
  border-radius: var(--r-sm);
  background: var(--surface-3);
}

.seg {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 9px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  font-family: inherit;
  font-size: 12px;
  color: var(--text-3);
  cursor: pointer;
  transition: 0.12s;
}

.seg:hover {
  color: var(--text);
}

.seg.on {
  background: var(--surface);
  color: var(--text);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.1);
}

.seg .n {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--text-3);
}

.seg.on .n {
  color: var(--text-2);
}

.board-bar .dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 99px;
}

.dot.red {
  background: var(--danger);
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.1);
}

.dot.amber {
  background: #d97706;
  box-shadow: 0 0 0 3px rgba(217, 119, 6, 0.1);
}

.bar-result {
  font-size: 11.5px;
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.bar-time {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.apps {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(320px, 100%), 1fr));
  gap: 16px;
}

.appcard {
  display: flex;
  flex-direction: column;
  position: relative;
  transition: border-color 0.16s, box-shadow 0.16s;
}

.appcard:hover {
  border-color: var(--border-2);
  box-shadow: 0 2px 10px rgba(16, 24, 40, 0.06);
}

.appcard-h {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px 14px 10px;
}

.app-ic {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 8px;
  background: var(--surface-3);
  color: var(--text-2);
  display: grid;
  place-items: center;
  font: 650 12px var(--mono);
}

.app-head-main {
  flex: 1;
  min-width: 0;
}

.app-t {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 14px;
  font-weight: 600;
}

.app-c {
  margin-top: 1px;
  font-size: 11.5px;
  color: var(--text-3);
}

.app-more {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  color: var(--text-3);
  cursor: pointer;
  display: grid;
  place-items: center;
}

.app-more:hover {
  background: var(--surface-3);
  color: var(--text);
}

.app-d {
  padding: 0 14px 10px;
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.5;
}

.groups {
  padding: 0 14px 12px;
}

.group {
  margin-bottom: 10px;
}

.group:last-child {
  margin-bottom: 0;
}

.group-l {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 6px;
  font-size: 11.5px;
  /* 中文 600 在 Windows 上多为合成粗体，小字号会发糊，用 500 + 更深的颜色拉开层级 */
  font-weight: 500;
  color: var(--text-2);
  letter-spacing: 0.03em;
}

.g-count {
  font-weight: 600;
  color: var(--text-2);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 25px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  background: var(--surface-3);
  color: var(--text);
  font: 500 12.5px var(--sans);
  cursor: pointer;
  transition: 0.14s;
}

.chip:hover {
  background: #eaedf3;
  border-color: var(--border-2);
}

.chip-nm {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 150px;
}

.chip-sub {
  border: 1px dashed #b9c0cc;
  background: #fcfcfd;
  color: var(--text-2);
}

/* 搜索命中的模块：主色浅底 + 实线描边，和普通 chip 区分开 */
.chip.chip-hit {
  border-color: var(--primary-100);
  background: var(--primary-50);
  color: var(--primary-700);
}

.chip.chip-hit:hover {
  border-color: var(--primary-100);
  background: var(--primary-100);
}

.search-empty .empty-acts {
  margin-top: 12px;
}

.more-row {
  display: flex;
  justify-content: center;
  padding: 18px 0 2px;
}

.more-n {
  margin-left: 6px;
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.chip-add {
  border: 1px dashed var(--border-2);
  background: transparent;
  color: var(--text-3);
  font-family: var(--sans);
  font-size: 11.5px;
}

.chip-add:hover,
.chip-add.on {
  border-color: var(--primary-100);
  background: var(--primary-50);
  color: var(--primary-600);
}

.appcard-f {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: auto;
  padding: 9px 14px;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
  color: var(--text-3);
}

.appcard-f b {
  font-weight: 600;
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.foot-add {
  margin-left: auto;
  border: 0;
  background: none;
  padding: 0;
  font-size: 11.5px;
  color: var(--text-3);
  cursor: pointer;
}

.foot-add:hover {
  color: var(--primary);
}

@media (max-width: 980px) {
  .board {
    grid-template-columns: minmax(0, 1fr);
  }

  .rail {
    position: static;
  }

  .rail-list {
    max-height: 32vh;
  }
}

@media (max-width: 600px) {
  .search {
    width: 100%;
  }

  .segs {
    width: 100%;
  }

  .seg {
    flex: 1;
    justify-content: center;
  }

  .bar-time {
    margin-left: 0;
  }
}
</style>
