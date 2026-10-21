<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">流水线接入</h2>
        <div class="fc-page-subtitle">
          三个只读接口；变量值不会被写入任何服务端日志。
        </div>
      </div>
      <button class="btn btn-primary" :disabled="loading" @click="copyAll">
        <ProtoIcon name="copy" :size="15" />复制全部示例
      </button>
    </div>

    <!-- 示例参数：应用 → 环境 → 模块，改动后三段示例实时重算 -->
    <div class="picker">
      <div class="card-head">
        <div class="card-title">示例范围</div>
        <span class="picker-hint">示例随选择实时更新</span>
      </div>

      <div v-if="loading" class="picker-body loading">正在加载应用与环境…</div>

      <div v-else-if="!apps.length" class="picker-body empty">
        <div class="empty-text">
          还没有应用。示例里的 application / modules 要用真实参数，先创建应用，这里会自动带出。
        </div>
        <router-link class="btn" to="/applications">去应用看板</router-link>
      </div>

      <div v-else class="picker-body">
        <FilterSelect
          label="应用"
          :model-value="appId"
          :options="appOptions"
          all-label="选择应用"
          @update:model-value="onApp"
        />
        <FilterSelect
          label="环境"
          :model-value="envId"
          :options="envOptions"
          all-label="选择环境"
          @update:model-value="onEnv"
        />

        <div class="mods">
          <span class="mods-label">模块</span>
          <div v-if="!appModules.length" class="mods-none">该应用还没有模块</div>
          <div v-else-if="!boundModules.length" class="mods-none">
            在此环境下没有绑定模块，去模块详情绑定后再回来
          </div>
          <div v-else class="chips">
            <button
              v-for="m in boundModules"
              :key="String(m.id)"
              type="button"
              class="modchip"
              :class="{ on: picked.has(String(m.id)) }"
              :title="m.name"
              @click="toggle(m)"
            >
              <span class="mc-code">{{ m.code }}</span>
              <span v-if="m.shared" class="mc-shared">共享</span>
            </button>
          </div>
          <button
            v-if="boundModules.length > 1"
            type="button"
            class="link-btn"
            @click="toggleAll"
          >
            {{ allPicked ? '全不选' : '全选' }}
          </button>
        </div>
      </div>

      <div v-if="!loading && apps.length" class="picker-sum">
        {{ summary }}
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">1 · 拉取变量（dotenv）</div>
        <div class="head-right">
          <span class="tag tag-plain">GET</span>
          <button class="icon-btn" title="复制这一段" @click="copy(linesConfig, '第 1 段')">
            <ProtoIcon name="copy" :size="14" />
          </button>
        </div>
      </div>
      <div class="card-body">
        <div class="code-wrap">
          <div class="code-bar"><span class="code-lang">bash</span></div>
          <pre class="code"><template v-for="(line, i) in linesConfig" :key="i"><span v-for="(seg, j) in line" :key="j" :class="seg.c">{{ seg.t }}</span>{{ i + 1 < linesConfig.length ? '\n' : '' }}</template></pre>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">2 · 批量下载文件（tar.gz）</div>
        <div class="head-right">
          <span class="tag tag-plain">POST</span>
          <button class="icon-btn" title="复制这一段" @click="copy(linesArtifacts, '第 2 段')">
            <ProtoIcon name="copy" :size="14" />
          </button>
        </div>
      </div>
      <div class="card-body">
        <div class="code-wrap">
          <div class="code-bar"><span class="code-lang">bash</span></div>
          <pre class="code"><template v-for="(line, i) in linesArtifacts" :key="i"><span v-for="(seg, j) in line" :key="j" :class="seg.c">{{ seg.t }}</span>{{ i + 1 < linesArtifacts.length ? '\n' : '' }}</template></pre>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">3 · 单文件下载</div>
        <div class="head-right">
          <span class="tag tag-plain">GET</span>
          <button class="icon-btn" title="复制这一段" @click="copy(linesFile, '第 3 段')">
            <ProtoIcon name="copy" :size="14" />
          </button>
        </div>
      </div>
      <div class="card-body">
        <div class="file-pick">
          <FilterSelect
            label="文件"
            :model-value="pickedFile"
            :options="fileOptions"
            all-label="选择文件"
            @update:model-value="onFile"
          />
          <span v-if="fileTarget" class="fp-note">v{{ fileTarget.version }}</span>
          <span v-else class="fp-note">所选模块下还没有文件</span>
        </div>
        <div class="code-wrap">
          <div class="code-bar"><span class="code-lang">bash</span></div>
          <pre class="code"><template v-for="(line, i) in linesFile" :key="i"><span v-for="(seg, j) in line" :key="j" :class="seg.c">{{ seg.t }}</span>{{ i + 1 < linesFile.length ? '\n' : '' }}</template></pre>
        </div>
      </div>
    </div>

    <div class="grid-2">
      <div class="card">
        <div class="card-head">
          <div class="card-title">错误码</div>
        </div>
        <div class="card-body kv-body">
          <div v-for="e in errorCodes" :key="e.code" class="kv">
            <span class="k mono">{{ e.code }}</span>
            <span class="v">{{ e.meaning }}</span>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <div class="card-title">dotenv 转义规则</div>
        </div>
        <div class="card-body">
          <pre class="code small"><template v-for="(line, i) in linesEscape" :key="i"><span v-for="(seg, j) in line" :key="j" :class="seg.c">{{ seg.t }}</span>{{ i + 1 < linesEscape.length ? '\n' : '' }}</template></pre>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/http'
import { envApi } from '@/api'
import FilterSelect from '@/components/FilterSelect.vue'
import ProtoIcon from '@/components/ProtoIcon.vue'
import type { BoardApp, BoardData, MiniModule } from '@/types/board'
import type { Id } from '@/types/id'

interface CandidateFile {
  id: Id
  fileKey: string
  currentVersion?: number | null
}

/** 参与示例的模块：私有模块与订阅来的共享模块都可能出现在同一应用下 */
interface CandidateModule {
  id: Id
  code: string
  name: string
  shared: boolean
}

/** 代码块按「行 → 片段」建模，片段带可选样式（c = 注释，s = 字符串） */
type Seg = { t: string; c?: 'c' | 's' }
type Line = Seg[]

const errorCodes = [
  { code: '401', meaning: 'Token 缺失 / 无效 / 过期 / 吊销' },
  { code: '403', meaning: '模块不属于该应用' },
  { code: '400', meaning: 'modules 缺失，或模块未绑定该环境' },
  { code: '404', meaning: '文件不存在' },
  { code: '429', meaning: '超过 60 次 / 分钟' }
]

const base = window.location.origin

const loading = ref(true)
const apps = ref<BoardApp[]>([])
const envs = ref<{ id: Id; code: string; name: string; sortOrder: number; isDefault: boolean }[]>([])
const appId = ref('')
const envId = ref('')
/** moduleId → 绑定的环境 ID（字符串，雪花 ID 不能走 Number） */
const bindings = ref<Record<string, string[]>>({})
/** `${moduleId}:${envId}` → 该环境下的文件 */
const cache = ref<Record<string, CandidateFile[]>>({})
const picked = ref<Set<string>>(new Set())
/** 第 3 段挑中的文件，格式 `moduleId:fileId` */
const pickedFile = ref('')

const currentApp = computed(() => apps.value.find(a => String(a.id) === appId.value) || null)
const currentEnv = computed(() => envs.value.find(e => String(e.id) === envId.value) || null)

const appOptions = computed(() => apps.value.map(a => ({ value: String(a.id), label: a.name })))
const envOptions = computed(() => envs.value.map(e => ({ value: String(e.id), label: e.name })))

const appModules = computed<CandidateModule[]>(() => {
  const a = currentApp.value
  if (!a) return []
  return [
    ...(a.privateModules || []).map(m => toCandidate(m, false)),
    ...(a.subscribedModules || []).map(m => toCandidate(m, true))
  ]
})

const boundModules = computed(() =>
  appModules.value.filter(m => (bindings.value[String(m.id)] || []).includes(envId.value))
)

const selectedModules = computed(() =>
  boundModules.value.filter(m => picked.value.has(String(m.id)))
)

const allPicked = computed(
  () => boundModules.value.length > 0 && selectedModules.value.length === boundModules.value.length
)

const appCode = computed(() => currentApp.value?.code || 'your-app')
const envCode = computed(() => currentEnv.value?.code || 'uat')
const moduleCodes = computed(() => selectedModules.value.map(m => m.code))

/** 第 2 段用：选中模块下出现过的文件名（去重，保持顺序） */
const fileKeys = computed(() => {
  const out: string[] = []
  for (const m of selectedModules.value) {
    for (const f of filesOf(m)) {
      if (!out.includes(f.fileKey)) out.push(f.fileKey)
    }
  }
  return out
})

/** 第 3 段用：选中模块下的全部文件，供用户挑一个 */
const fileOptions = computed(() => {
  const out: { value: string; label: string }[] = []
  for (const m of selectedModules.value) {
    for (const f of filesOf(m)) {
      out.push({ value: `${String(m.id)}:${String(f.id)}`, label: `${m.code} / ${f.fileKey}` })
    }
  }
  return out
})

/** 第 3 段用：当前挑中的文件（默认第一个） */
const fileTarget = computed(() => {
  const pick = pickedFile.value
  if (!pick) return null
  const [moduleId, fileId] = pick.split(':')
  const m = selectedModules.value.find(x => String(x.id) === moduleId)
  if (!m) return null
  const f = filesOf(m).find(x => String(x.id) === fileId)
  if (!f) return null
  return {
    moduleId,
    fileId,
    version: f.currentVersion ?? 1,
    ref: `${m.code}/${f.fileKey}`
  }
})

/** 选择项变了（换应用/环境/模块或文件列表刷新）后，把挑中的文件拉回有效范围 */
function syncPickedFile() {
  const opts = fileOptions.value
  if (opts.some(o => o.value === pickedFile.value)) return
  pickedFile.value = opts.length ? opts[0].value : ''
}

const summary = computed(() => {
  if (!currentApp.value) return '选择应用后即可生成示例'
  const parts = [currentApp.value.code, currentEnv.value?.code || '未选环境']
  if (!boundModules.value.length) {
    parts.push('此环境下没有模块')
  } else if (!moduleCodes.value.length) {
    parts.push('已取消全部模块')
  } else {
    const shown = moduleCodes.value.slice(0, 4).join('、')
    const more = moduleCodes.value.length > 4 ? ` 等 ${moduleCodes.value.length} 个` : ''
    parts.push(`${moduleCodes.value.length} 个模块：${shown}${more}`)
  }
  return `当前示例：${parts.join(' · ')}`
})

const TOKEN_ARG = '"Authorization: Bearer $FCLOUD_TOKEN"'

const linesConfig = computed<Line[]>(() => {
  const modules = moduleCodes.value.length ? moduleCodes.value.map(enc).join(',') : 'your-module'
  const url =
    `${base}/api/v1/pipeline/config?application=${enc(appCode.value)}` +
    `&environment=${enc(envCode.value)}&modules=${modules}`
  const out: Line[] = [
    [{ t: '# modules 必填，逗号分隔；按请求顺序输出', c: 'c' }],
    [{ t: 'curl -sS \\' }],
    [{ t: '  -H ' }, { t: TOKEN_ARG, c: 's' }, { t: ' \\' }],
    [{ t: '  ' }, { t: `"${url}"`, c: 's' }],
    [{ t: '# 响应 text/plain，每个模块以 # module: {code} 开头', c: 'c' }]
  ]
  if (!moduleCodes.value.length) {
    out.splice(1, 0, [
      { t: '# 还没选中模块，modules 先占位；改成 例如 db-config,common-notify', c: 'c' }
    ])
  }
  return out
})

const linesArtifacts = computed<Line[]>(() => {
  const body = JSON.stringify({
    application: appCode.value,
    environment: envCode.value,
    modules: moduleCodes.value.length ? moduleCodes.value : ['your-module'],
    files: fileKeys.value.length ? fileKeys.value : ['your-file']
  })
  const out: Line[] = [
    [{ t: 'curl -sS -X POST \\' }],
    [{ t: '  -H ' }, { t: TOKEN_ARG, c: 's' }, { t: ' \\' }],
    [{ t: '  -H ' }, { t: '"Content-Type: application/json"', c: 's' }, { t: ' \\' }],
    [{ t: '  -d ' }, { t: shellQuote(body), c: 's' }, { t: ' \\' }],
    [{ t: '  ' }, { t: `"${base}/api/v1/pipeline/artifacts"`, c: 's' }, { t: ' -o artifacts.tar.gz' }]
  ]
  if (!fileKeys.value.length) {
    out.push([{ t: '# 选中的模块在此环境下没有文件，files 换成真实文件名', c: 'c' }])
  }
  return out
})

const linesFile = computed<Line[]>(() => {
  const t = fileTarget.value
  const url = t
    ? `${base}/api/v1/pipeline/files/${t.moduleId}/${t.fileId}?version=${t.version}`
    : `${base}/api/v1/pipeline/files/{moduleId}/{fileId}?version=3`
  const out: Line[] = [
    [{ t: '# version 省略 = 最新版本', c: 'c' }],
    [{ t: 'curl -sS \\' }],
    [{ t: '  -H ' }, { t: TOKEN_ARG, c: 's' }, { t: ' \\' }],
    [{ t: '  ' }, { t: `"${url}"`, c: 's' }, { t: ' \\' }],
    [{ t: '  -O -J' }]
  ]
  out.push(
    t
      ? [{ t: `# 取自 ${t.ref}，换成别的模块文件改 path 里的 id 即可`, c: 'c' }]
      : [{ t: '# 选中模块还没有文件，先用第 2 段确认文件名', c: 'c' }]
  )
  return out
})

const linesEscape: Line[] = [
  [{ t: '# 含空格 / # / 单引号 / 换行 / $ 时', c: 'c' }],
  [{ t: 'DB_PASSWORD=' }, { t: "'p@ss word'", c: 's' }],
  [{ t: 'CERT=' }, { t: "'it'\\''s fine'", c: 's' }],
  [{ t: 'PLAIN=10.0.2.9' }]
]

function toCandidate(m: MiniModule, shared: boolean): CandidateModule {
  return { id: m.id, code: m.code, name: m.name, shared }
}

function enc(v: string): string {
  return encodeURIComponent(v)
}

/** 单引号包裹的 shell 参数，内部的单引号按 ' → '\'' 转义 */
function shellQuote(v: string): string {
  return `'${v.replace(/'/g, "'\\''")}'`
}

function filesOf(m: CandidateModule): CandidateFile[] {
  return cache.value[`${String(m.id)}:${envId.value}`] || []
}

function plain(lines: Line[]): string {
  return lines.map(line => line.map(s => s.t).join('')).join('\n')
}

async function copy(lines: Line[], what: string) {
  try {
    await navigator.clipboard.writeText(plain(lines))
    ElMessage.success(`${what}已复制`)
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

async function copyAll() {
  const all = [linesConfig.value, linesArtifacts.value, linesFile.value]
    .map((lines, i) => `# ${i + 1}. ${['拉取变量（dotenv）', '批量下载文件（tar.gz）', '单文件下载'][i]}\n${plain(lines)}`)
    .join('\n\n')
  try {
    await navigator.clipboard.writeText(all)
    ElMessage.success('示例已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

function toggle(m: CandidateModule) {
  const id = String(m.id)
  const next = new Set(picked.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  picked.value = next
  syncPickedFile()
  if (next.has(id)) void loadFiles([m]).then(syncPickedFile)
}

function toggleAll() {
  const next = new Set<string>()
  if (!allPicked.value) boundModules.value.forEach(m => next.add(String(m.id)))
  picked.value = next
  syncPickedFile()
  if (next.size) void loadFiles(boundModules.value).then(syncPickedFile)
}

function onFile(v: string) {
  pickedFile.value = v
}

/** 模块 → 环境绑定，用来按当前环境筛掉未绑定的模块 */
async function loadBindings(): Promise<void> {
  const list = appModules.value
  const settled = await Promise.allSettled(
    list.map(m => api.get<Id[]>(`/api/console/admin/modules/${m.id}/environment-ids`))
  )
  const map: Record<string, string[]> = {}
  list.forEach((m, i) => {
    const r = settled[i]
    map[String(m.id)] = r.status === 'fulfilled' ? (r.value || []).map(String) : []
  })
  bindings.value = map
}

/** 按选中模块补齐文件列表（按 模块+环境 缓存，切换环境会重新取） */
async function loadFiles(mods: CandidateModule[]): Promise<void> {
  const env = envId.value
  const todo = mods.filter(m => !cache.value[`${String(m.id)}:${env}`])
  if (!todo.length) return
  const settled = await Promise.allSettled(
    todo.map(m => api.get<CandidateFile[]>(`/api/console/modules/${m.id}/files`, { environmentId: env }))
  )
  const next = { ...cache.value }
  todo.forEach((m, i) => {
    const r = settled[i]
    next[`${String(m.id)}:${env}`] = r.status === 'fulfilled' ? r.value || [] : []
  })
  cache.value = next
}

/** 应用或环境变化后重建：绑定 → 默认全选 → 文件 */
async function applyScope(): Promise<void> {
  if (!currentApp.value) return
  await loadBindings()
  const bound = boundModules.value
  picked.value = new Set(bound.map(m => String(m.id)))
  syncPickedFile()
  await loadFiles(bound)
  syncPickedFile()
}

async function onApp(v: string) {
  appId.value = v
  cache.value = {}
  await applyScope()
}

async function onEnv(v: string) {
  envId.value = v
  await applyScope()
}

onMounted(async () => {
  try {
    const [board, list] = await Promise.all([
      api.get<BoardData>('/api/console/applications/board'),
      envApi.list()
    ])
    apps.value = [...(board.applications || [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    envs.value = [...(list || [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    const firstApp = apps.value[0]
    const defaultEnv = envs.value.find(e => e.isDefault) || envs.value[0]
    if (firstApp) appId.value = String(firstApp.id)
    if (defaultEnv) envId.value = String(defaultEnv.id)
    if (firstApp && defaultEnv) await applyScope()
  } catch {
    ElMessage.warning('应用与环境加载失败，示例已回退为占位参数')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.picker {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  margin-bottom: 16px;
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  margin-bottom: 16px;
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
  font-size: 13.5px;
  font-weight: 600;
}

.card-body {
  padding: 14px 16px;
}

.file-pick {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
  margin-bottom: 10px;
}

.fp-note {
  font: 12px var(--mono);
  color: var(--text-3);
}

/* 文件名比普通筛选项长，放宽一点再省略 */
.file-pick :deep(.field b) {
  max-width: 240px;
}

.picker-hint {
  font-size: 12px;
  color: var(--text-3);
}

.picker-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 12px;
  padding: 14px 16px;
}

.picker-body.loading,
.picker-body.empty {
  color: var(--text-3);
  font-size: 12.5px;
}

.picker-body.empty {
  justify-content: space-between;
}

.empty-text {
  max-width: 640px;
  line-height: 1.6;
}

.picker-sum {
  padding: 0 16px 13px;
  font-size: 12px;
  color: var(--text-3);
}

.mods {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.mods-label {
  font-size: 12.5px;
  color: var(--text-3);
}

.mods-none {
  font-size: 12.5px;
  color: var(--text-3);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.modchip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--border-2);
  border-radius: 14px;
  background: var(--surface);
  font-family: inherit;
  font-size: 12px;
  color: var(--text-2);
  cursor: pointer;
  transition: background 0.12s, border-color 0.12s, color 0.12s;
}

.modchip:hover {
  border-color: var(--primary);
  color: var(--text);
}

.modchip.on {
  background: var(--primary-50);
  border-color: var(--primary);
  color: var(--primary-700);
  font-weight: 500;
}

.mc-code {
  font-family: var(--mono);
}

.mc-shared {
  font-size: 10.5px;
  color: var(--text-3);
  background: var(--surface-3);
  border-radius: 4px;
  padding: 1px 4px;
  font-family: var(--sans);
}

.modchip.on .mc-shared {
  background: var(--primary-100);
  color: var(--primary-700);
}

.link-btn {
  border: 0;
  background: transparent;
  padding: 0;
  font-family: inherit;
  font-size: 12px;
  color: var(--primary);
  cursor: pointer;
}

.link-btn:hover {
  color: var(--primary-700);
  text-decoration: underline;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
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

/* 代码块：与文件预览（FilePreviewDrawer）同一套浅色度量，
   深色底在浅色文档里是全站唯一的"终端"异色，且 12.5px/1.9 字小行松 */
.code-wrap {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--r);
  background: var(--surface-3);
}

.code-bar {
  display: flex;
  align-items: center;
  height: 28px;
  padding: 0 12px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
}

.code {
  margin: 0;
  padding: 12px 14px;
  background: transparent;
  color: var(--text);
  font-family: var(--mono);
  font-size: 13px;
  line-height: 21px;
  font-variant-ligatures: none;
  font-feature-settings: "calt" 0, "liga" 0, "clig" 0;
  -moz-tab-size: 4;
  tab-size: 4;
  overflow: auto;
  white-space: pre;
}

.code.small {
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 20px;
}

.code .c {
  color: var(--text-3);
}

.code .s {
  color: #047857;
}

.code-lang {
  font-family: var(--mono);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
  color: var(--text-3);
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}

.kv-body {
  padding: 4px 16px 10px;
}

.kv {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 0;
  font-size: 12.5px;
  border-bottom: 1px solid #f4f5f8;
}

.kv:last-child {
  border-bottom: 0;
}

.kv .k {
  color: var(--text-3);
  width: 36px;
  flex: 0 0 36px;
}

.kv .v {
  color: var(--text);
  font-weight: 500;
  text-align: right;
}

@media (max-width: 980px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }

  .picker-body {
    flex-direction: column;
    align-items: stretch;
  }

  .picker-body.empty {
    align-items: flex-start;
  }
}
</style>
