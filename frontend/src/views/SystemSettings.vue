<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">系统管理</h2>
        <div class="fc-page-subtitle">
          GitLab 接入用于从仓库导入应用；制品库存放备份；MCP 服务提供给 AI 客户端读写配置。
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">GitLab</div>
        <span v-if="conn" class="tag tag-ok">已连接 · {{ conn }}</span>
        <span v-else-if="hasToken" class="tag tag-plain">已配置，未验证</span>
        <span v-else class="tag tag-warn">未配置</span>
      </div>
      <div class="card-body gcfg">
        <label class="fld">
          <span class="fld-lb">
            Base URL
            <span class="fld-opt">私有部署填内网地址，例如 http://gitlab.corp.com</span>
          </span>
          <el-input
            v-model="baseUrl"
            class="k-input"
            placeholder="https://gitlab.com"
            @blur="baseUrl = baseUrl.trim()"
          />
        </label>

        <label class="fld">
          <span class="fld-lb">
            Personal Access Token
            <span class="fld-opt">
              {{ tokenPreview ? `当前 ${tokenPreview}，留空表示不修改` : '需要 api 或 read_api 权限' }}
            </span>
          </span>
          <el-input
            v-model="token"
            type="password"
            show-password
            class="k-input"
            autocomplete="new-password"
            :placeholder="hasToken ? '留空表示保持现有 Token' : 'glpat-xxxxxxxxxxxx'"
          />
          <span class="fld-hint">
            Token 用主密钥加密后落库，接口只返回尾 4 位；换主密钥会导致已存 Token 无法解密。
          </span>
        </label>

        <div class="callout">
          <ProtoIcon name="alert" :size="13" class="c-ic" />
          <span>
            配置好后，看板的「从 GitLab 导入」会列出你有权限的项目；导入时自动带出
            code、名称和仓库地址，重复导入同一项目会被拒绝。
          </span>
        </div>
      </div>
      <div class="card-foot">
        <span>{{ updatedAt ? `最近更新 ${fmt(updatedAt)}` : '还没有保存过配置' }}</span>
        <div class="foot-actions">
          <button class="btn btn-sm" :disabled="testing" @click="testConn">
            <ProtoIcon name="sync" :size="13" />{{ testing ? '测试中…' : '测试连接' }}
          </button>
          <button class="btn btn-primary btn-sm" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">制品库（JFrog Artifactory 兼容）</div>
        <span v-if="artifact.enabled" class="tag tag-ok">已开启 · 备份异地留一份</span>
        <span v-else class="tag tag-plain">未开启</span>
      </div>
      <div class="card-body gcfg">
        <div class="mcp-row">
          <div class="mcp-info">
            <span class="mcp-name">上传备份到制品库</span>
            <span class="mcp-desc">
              每份备份在本地生成成功后，再用 Artifactory 的 deploy 接口推一份；本地副本始终保留
            </span>
          </div>
          <span class="mcp-impact">{{ artifact.enabled ? '开启' : '关闭' }}</span>
          <el-switch v-model="artifact.enabled" />
        </div>

        <label class="fld">
          <span class="fld-lb">
            Artifactory 地址
            <span class="fld-opt">到域名或上下文根为止，不要带 /artifactory</span>
          </span>
          <el-input
            v-model="artifact.baseUrl"
            class="k-input"
            placeholder="https://gitee.corp.com"
            @blur="artifact.baseUrl = artifact.baseUrl.trim()"
          />
        </label>

        <div class="artifact-grid">
          <label class="fld">
            <span class="fld-lb">仓库 Key<span class="fld-opt">宿主仓库，如 xxx-local</span></span>
            <el-input
              v-model="artifact.repo"
              class="k-input"
              placeholder="fcloud-backups-local"
              @blur="artifact.repo = artifact.repo.trim()"
            />
          </label>
          <label class="fld">
            <span class="fld-lb">目录<span class="fld-opt">仓库内的存放路径</span></span>
            <el-input
              v-model="artifact.dir"
              class="k-input"
              placeholder="fcloud-backups"
            />
          </label>
        </div>

        <div class="artifact-grid">
          <label class="fld">
            <span class="fld-lb">用户名<span class="fld-opt">选填，填了就走 Basic 认证</span></span>
            <el-input
              v-model="artifact.username"
              class="k-input"
              placeholder="留空则用 Bearer"
            />
          </label>
          <label class="fld">
            <span class="fld-lb">
              Token
              <span class="fld-opt">
                {{ artifact.tokenPreview ? `当前 ${artifact.tokenPreview}，留空不改` : '部署令牌 / API Key' }}
              </span>
            </span>
            <el-input
              v-model="artifact.token"
              type="password"
              show-password
              class="k-input"
              autocomplete="new-password"
              :placeholder="artifact.hasToken ? '留空表示保持现有令牌' : '粘贴部署令牌'"
            />
          </label>
        </div>

        <span class="fld-hint">
          上传走 <code>PUT /artifactory/{仓库}/{目录}/{文件名}</code>，清单走
          <code>/api/storage</code>。令牌用主密钥加密后落库，接口只返回尾 4 位；单文件上限
          {{ artifact.maxUploadMb }} MB（Artifactory 本身不限，这里只做兜底）。
        </span>
        <div class="callout">
          <ProtoIcon name="alert" :size="13" class="c-ic" />
          <span>
            备份页会多出「制品库」列和恢复来源选项。制品库只保留最近
            {{ backupRetain }} 份，与本地保留份数一致；超出部分自动删除。
          </span>
        </div>
      </div>
      <div class="card-foot">
        <span>
          {{ artifact.updatedAt ? `最近更新 ${fmt(artifact.updatedAt)}` : '还没有保存过配置' }}
          <span v-if="artifactConn" class="tag tag-ok foot-tag">{{ artifactConn }}</span>
        </span>
        <div class="foot-actions">
          <button class="btn btn-sm" :disabled="artifactTesting" @click="testArtifact">
            <ProtoIcon name="sync" :size="13" />{{ artifactTesting ? '测试中…' : '测试连接' }}
          </button>
          <button class="btn btn-primary btn-sm" :disabled="artifactSaving" @click="saveArtifact">
            {{ artifactSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">MCP 服务</div>
        <span v-if="!mcpForm.enabled" class="tag tag-plain">已停用</span>
        <span v-else class="tag tag-ok">运行中 · {{ activeToolCount }} 个工具</span>
      </div>
      <div class="card-body mcfg">
        <div class="mcp-row">
          <div class="mcp-info">
            <span class="mcp-name">启用 MCP 端点</span>
            <span class="mcp-desc">关闭后 <code>/mcp</code> 返回 404，所有 AI 客户端断开</span>
          </div>
          <span class="mcp-impact">{{ toolCounts.read }} 个只读工具</span>
          <el-switch v-model="mcpForm.enabled" />
        </div>

        <div class="mcp-row" :class="{ dim: !mcpForm.enabled }">
          <div class="mcp-info">
            <span class="mcp-name">允许写入</span>
            <span class="mcp-desc">
              改值、导入 dotenv、建删变量、上传文件；仍需令牌带 <code>mcp:write</code>
            </span>
          </div>
          <span class="mcp-impact">{{ toolCounts.write }} 个写工具</span>
          <el-switch v-model="mcpForm.writeEnabled" :disabled="!mcpForm.enabled" />
        </div>

        <div class="mcp-row danger" :class="{ dim: !mcpForm.enabled }">
          <div class="mcp-info">
            <span class="mcp-name">
              允许读取密钥明文
              <span class="tag tag-danger">高危</span>
            </span>
            <span class="mcp-desc">
              开启后持有 <code>mcp:secret</code> 的管理员令牌可读到加密变量的明文，
              每次调用都写高危审计
            </span>
          </div>
          <span class="mcp-impact">{{ toolCounts.secret }} 个工具</span>
          <el-switch
            v-model="mcpForm.revealEnabled"
            :disabled="!mcpForm.enabled"
            :before-change="confirmReveal"
          />
        </div>

        <div class="mcp-limits">
          <span>
            限流 {{ mcpLimits.ratePerMinute }} 次/分 · 单次结果上限
            {{ Math.round(mcpLimits.maxResultBytes / 1024) }} KB · 会话
            {{ mcpLimits.sessionTtlMinutes }} 分钟
          </span>
          <span class="mcp-limits-note">这三项是部署参数，改动需重启</span>
        </div>

        <div class="mcp-tutorial">
          <button class="tut-toggle" type="button" @click="tutOpen = !tutOpen">
            <span class="tut-title">接入教程 · 3 步让 AI 客户端连上</span>
            <span class="tut-arrow">{{ tutOpen ? '收起' : '展开' }}</span>
          </button>

          <div v-if="tutOpen" class="tut-body">
            <div class="tut-step">
              <span class="tut-num">1</span>
              <div class="tut-text">
                <b>签发一个访问令牌</b>
                <div class="tut-desc">
                  <router-link class="tut-link" to="/tokens">Token 管理 → AI 访问令牌 → 新建访问令牌</router-link>。只读场景用默认的
                  <code>mcp:read</code>；要改配置加勾 <code>mcp:write</code>；要读加密变量的明文加勾
                  <code>mcp:secret</code>。明文只显示一次，关闭后无法再查看。
                </div>
              </div>
            </div>

            <div class="tut-step">
              <span class="tut-num">2</span>
              <div class="tut-text">
                <b>配置客户端</b>
                <div class="tut-code">
                  <button class="tut-copy" type="button" @click="copySnippet">复制</button>
                  <pre>{{ clientSnippet }}</pre>
                </div>
              </div>
            </div>

            <div class="tut-step">
              <span class="tut-num">3</span>
              <div class="tut-text">
                <b>验证连通</b>
                <div class="tut-code">
                  <pre>{{ curlSnippet }}</pre>
                </div>
                <div class="tut-note">
                  返回带 <code>protocolVersion</code> 的 JSON 即已连通；401 说明令牌不对。
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div v-if="mcpDirty" class="card-foot mcp-foot">
        <span class="dirty-hint">有未保存的更改，保存后 5 秒内对所有客户端生效</span>
        <div class="foot-actions">
          <button class="btn" @click="resetMcp">撤销</button>
          <button class="btn btn-primary" :disabled="mcpSaving" @click="saveMcp">
            {{ mcpSaving ? '保存中…' : '保存并生效' }}
          </button>
        </div>
      </div>
      <div v-else class="card-foot">
        <span>
          {{ mcpPersisted ? `最近更新 ${mcpUpdatedAt ? fmt(mcpUpdatedAt) : '—'}` : '当前沿用部署默认值，尚未在页面修改过' }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { COPY_FAIL_HINT, copyText } from '@/utils/clipboard'
import ProtoIcon from '@/components/ProtoIcon.vue'
import { api } from '@/api/http'

interface GitLabVO {
  baseUrl: string
  hasToken: boolean
  tokenPreview?: string | null
  updatedAt?: string | null
}

const baseUrl = ref('https://gitlab.com')
const token = ref('')
const hasToken = ref(false)
const tokenPreview = ref<string | null>(null)
const updatedAt = ref<string | null>(null)
const conn = ref('')
const saving = ref(false)
const testing = ref(false)

// ---------------- MCP 服务开关 ----------------
interface McpVO {
  enabled: boolean
  writeEnabled: boolean
  revealEnabled: boolean
  persisted: boolean
  updatedAt?: string | null
  toolCounts: { read: number; write: number; secret: number }
  limits: { ratePerMinute: number; maxResultBytes: number; sessionTtlMinutes: number }
}

const mcpForm = reactive({ enabled: true, writeEnabled: false, revealEnabled: false })
const mcpOriginal = reactive({ enabled: true, writeEnabled: false, revealEnabled: false })
const toolCounts = reactive({ read: 0, write: 0, secret: 0 })
const mcpLimits = reactive({ ratePerMinute: 120, maxResultBytes: 32768, sessionTtlMinutes: 30 })
const mcpPersisted = ref(false)
const mcpUpdatedAt = ref<string | null>(null)
const mcpSaving = ref(false)
const tutOpen = ref(true)

/** 接入教程里的地址跟着当前站点走，复制出来就能直接用 */
const mcpUrl = `${location.origin}/mcp`
const clientSnippet = computed(
  () => `{
  "mcpServers": {
    "fcloud-ci-manager": {
      "url": "${mcpUrl}",
      "headers": { "Authorization": "Bearer chub_pat_xxxxxxxx" }
    }
  }
}`
)
const curlSnippet = [
  `curl -X POST ${mcpUrl} \\`,
  '  -H "Authorization: Bearer chub_pat_xxxxxxxx" \\',
  '  -H "Content-Type: application/json" \\',
  `-d '{"jsonrpc":"2.0","id":1,"method":"ping"}'`
].join('\n')

async function copySnippet() {
  if (await copyText(clientSnippet.value)) {
    ElMessage.success('已复制，把令牌替换成自己的即可')
  } else {
    ElMessage.warning({ message: COPY_FAIL_HINT, duration: 6000 })
  }
}

const mcpDirty = computed(
  () =>
    mcpForm.enabled !== mcpOriginal.enabled ||
    mcpForm.writeEnabled !== mcpOriginal.writeEnabled ||
    mcpForm.revealEnabled !== mcpOriginal.revealEnabled
)

/** 当前实际会暴露给客户端的工具数（只读常开，另两个看开关） */
const activeToolCount = computed(() => {
  if (!mcpForm.enabled) return 0
  return (
    toolCounts.read +
    (mcpForm.writeEnabled ? toolCounts.write : 0) +
    (mcpForm.revealEnabled ? toolCounts.secret : 0)
  )
})

function confirmReveal(): Promise<boolean> {
  return ElMessageBox.confirm(
    '开启后，持有 mcp:secret 的管理员令牌可以读取加密变量明文，每次调用都会写高危审计。',
    '开启明文读取',
    { type: 'warning', confirmButtonText: '开启', cancelButtonText: '取消' }
  )
    .then(() => true)
    .catch(() => false)
}

async function loadMcp() {
  const d = await api.get<McpVO>('/api/console/admin/mcp')
  mcpForm.enabled = d.enabled
  mcpForm.writeEnabled = d.writeEnabled
  mcpForm.revealEnabled = d.revealEnabled
  Object.assign(mcpOriginal, mcpForm)
  Object.assign(toolCounts, d.toolCounts)
  Object.assign(mcpLimits, d.limits)
  mcpPersisted.value = d.persisted
  mcpUpdatedAt.value = d.updatedAt || null
}

function resetMcp() {
  Object.assign(mcpForm, mcpOriginal)
}

async function saveMcp() {
  mcpSaving.value = true
  try {
    await api.put('/api/console/admin/mcp', {
      enabled: mcpForm.enabled,
      writeEnabled: mcpForm.writeEnabled,
      revealEnabled: mcpForm.revealEnabled
    })
    ElMessage.success('已保存，5 秒内对所有客户端生效')
    await loadMcp()
  } finally {
    mcpSaving.value = false
  }
}

// ---------------- 制品库（JFrog Artifactory 兼容） ----------------
interface ArtifactVO {
  enabled: boolean
  baseUrl?: string | null
  username?: string | null
  repo?: string | null
  dir: string
  hasToken: boolean
  tokenPreview?: string | null
  updatedAt?: string | null
  maxUploadMb: number
  retain: number
}

const artifact = reactive({
  enabled: false,
  baseUrl: '',
  username: '',
  repo: '',
  dir: 'fcloud-backups',
  token: '',
  hasToken: false,
  tokenPreview: null as string | null,
  updatedAt: null as string | null,
  maxUploadMb: 2048
})
const backupRetain = ref(3)
const artifactSaving = ref(false)
const artifactTesting = ref(false)
const artifactConn = ref('')

async function loadArtifact() {
  const d = await api.get<ArtifactVO>('/api/console/admin/settings/artifact')
  artifact.enabled = d.enabled
  artifact.baseUrl = d.baseUrl || ''
  artifact.username = d.username || ''
  artifact.repo = d.repo || ''
  artifact.dir = d.dir || 'fcloud-backups'
  artifact.hasToken = d.hasToken
  artifact.tokenPreview = d.tokenPreview || null
  artifact.updatedAt = d.updatedAt || null
  artifact.maxUploadMb = d.maxUploadMb
  backupRetain.value = d.retain
  artifact.token = ''
}

async function saveArtifact() {
  if (artifact.enabled && (!artifact.baseUrl.trim() || !artifact.repo.trim())) {
    ElMessage.warning('开启后 Artifactory 地址与仓库 Key 必填')
    return
  }
  artifactSaving.value = true
  try {
    await api.put('/api/console/admin/settings/artifact', {
      enabled: artifact.enabled,
      baseUrl: artifact.baseUrl.trim(),
      username: artifact.username.trim(),
      repo: artifact.repo.trim(),
      dir: artifact.dir.trim() || 'fcloud-backups',
      // 空表示不修改，避免把脱敏回显覆盖成真 token
      token: artifact.token.trim() || null
    })
    ElMessage.success('已保存')
    await loadArtifact()
  } finally {
    artifactSaving.value = false
  }
}

async function testArtifact() {
  artifactTesting.value = true
  artifactConn.value = ''
  try {
    const d = await api.post<{ repo: string; dir: string; fileCount: number }>(
      '/api/console/admin/settings/artifact/test'
    )
    artifactConn.value = `${d.repo} · ${d.fileCount} 个备份`
    ElMessage.success(`连接成功：${d.repo}`)
  } catch {
    // 错误信息已由拦截器提示
  } finally {
    artifactTesting.value = false
  }
}

function fmt(v: string) {
  return new Date(v).toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
}

async function load() {
  const d = await api.get<GitLabVO>('/api/console/admin/settings/gitlab')
  baseUrl.value = d.baseUrl || 'https://gitlab.com'
  hasToken.value = d.hasToken
  tokenPreview.value = d.tokenPreview || null
  updatedAt.value = d.updatedAt || null
}

async function save() {
  if (!baseUrl.value.trim()) {
    ElMessage.warning('Base URL 必填')
    return
  }
  saving.value = true
  try {
    await api.put('/api/console/admin/settings/gitlab', {
      baseUrl: baseUrl.value.trim(),
      token: token.value.trim() || null
    })
    token.value = ''
    ElMessage.success('已保存')
    await load()
  } finally {
    saving.value = false
  }
}

async function testConn() {
  testing.value = true
  conn.value = ''
  try {
    const me = await api.post<{ username: string; name: string }>(
      '/api/console/admin/settings/gitlab/test'
    )
    conn.value = `@${me.username}`
    ElMessage.success(`连接成功：${me.name || me.username}`)
  } catch {
    // 错误信息已由拦截器提示
  } finally {
    testing.value = false
  }
}

onMounted(() => {
  load()
  loadMcp()
  loadArtifact()
})
</script>

<style scoped>
.gcfg {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 640px;
}

.artifact-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 14px;
}

.foot-tag {
  margin-left: 8px;
}


.fld {
  display: flex;
  flex-direction: column;
  gap: 5px;
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

.k-input :deep(.el-input__inner) {
  font-family: var(--mono);
  font-size: 12.5px;
}

.callout {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 9px 11px;
  border-radius: var(--r-sm);
  background: var(--primary-50);
  border: 1px solid var(--primary-100);
  color: var(--text-2);
  font-size: 11.5px;
  line-height: 1.55;
}

.callout .c-ic {
  margin-top: 1px;
  color: var(--primary);
}

/* ---------- MCP 服务 ---------- */
.mcfg {
  max-width: 640px;
}

.mcp-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 0;
  border-bottom: 1px solid #f0f1f5;
}

.mcp-row.dim {
  opacity: 0.55;
}

.mcp-row.danger .mcp-name {
  color: var(--danger);
}

.mcp-info {
  flex: 1;
  min-width: 0;
}

.mcp-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
}

.mcp-desc {
  display: block;
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.55;
}

.mcp-desc code,
.mcp-limits code {
  font-family: var(--mono, monospace);
  background: var(--surface-3);
  padding: 1px 4px;
  border-radius: 4px;
  font-size: 11px;
}

.mcp-impact {
  flex: 0 0 auto;
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.mcp-limits {
  padding-top: 12px;
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.6;
}

.mcp-limits-note {
  display: block;
  margin-top: 2px;
}

.mcp-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.dirty-hint {
  font-size: 12px;
  color: var(--warn);
}

.foot-actions {
  display: flex;
  gap: 8px;
}

/* ---------- 接入教程 ---------- */
.mcp-tutorial {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f0f1f5;
}

.tut-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.tut-title {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
}

.tut-toggle:hover .tut-title {
  color: var(--primary);
}

.tut-arrow {
  font-size: 12px;
  color: var(--primary);
}

.tut-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 12px;
}

.tut-step {
  display: flex;
  gap: 9px;
}

.tut-num {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 50%;
  background: var(--primary-50);
  color: var(--primary);
  font-size: 11px;
  font-weight: 500;
}

.tut-text {
  min-width: 0;
  flex: 1;
}

.tut-text b {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
}

.tut-desc {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-2);
}

.tut-desc code,
.tut-note code {
  font-family: var(--mono, monospace);
  background: var(--surface-3);
  padding: 1px 4px;
  border-radius: 4px;
  font-size: 11px;
}

.tut-code {
  position: relative;
  margin-top: 7px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-3);
  overflow-x: auto;
}

.tut-code pre {
  margin: 0;
  font-family: var(--mono, monospace);
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--text-2);
  white-space: pre;
}

.tut-copy {
  position: absolute;
  top: 6px;
  right: 6px;
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: 5px;
  background: #fff;
  color: var(--text-2);
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
}

.tut-copy:hover {
  color: var(--primary);
  border-color: var(--primary-100);
}

.tut-note {
  margin-top: 6px;
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--text-3);
}

.tut-link {
  color: var(--primary);
  text-decoration: none;
  border-bottom: 1px solid transparent;
}

.tut-link:hover {
  border-bottom-color: var(--primary-100);
}
</style>
