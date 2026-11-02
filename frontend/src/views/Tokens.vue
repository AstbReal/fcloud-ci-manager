<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">{{ isPatTab ? 'AI 访问令牌' : 'Token 管理' }}</h2>
        <div class="fc-page-subtitle">
          <template v-if="isPatTab">
            AI 客户端（WorkBuddy / Claude Desktop / Cursor）用这个令牌访问 MCP。
            权限跟随你的账号，所有写操作记入审计。
          </template>
          <template v-else>
            全局 Token：一个 Token 可拉取任意应用、任意环境的配置，具体拉哪个由请求参数决定。
          </template>
        </div>
      </div>
      <div class="ph-actions">
        <button v-if="isPatTab" class="btn btn-primary" @click="openPatCreate">
          <ProtoIcon name="key" :size="15" />新建访问令牌
        </button>
        <button v-else class="btn btn-primary" @click="openCreate">
          <ProtoIcon name="key" :size="15" />签发 Token
        </button>
      </div>
    </div>

    <div v-if="isAdmin" class="tk-tabs">
      <button :class="{ active: tab === 'pipeline' }" @click="tab = 'pipeline'">
        流水线 Token
      </button>
      <button :class="{ active: tab === 'pat' }" @click="tab = 'pat'">
        AI 访问令牌
      </button>
    </div>

    <!-- ============ 流水线 Token ============ -->
    <template v-if="!isPatTab">
      <div class="callout">
        <el-icon :size="15"><InfoFilled /></el-icon>
        <div>
          <b>明文只展示一次。</b>关闭后不可再次查看，只能吊销重签。请立即复制到 CI 变量中。
        </div>
      </div>

      <div v-if="plainText" class="card secret-card">
        <div class="secret-label">新 Token（{{ newTokenName }}）已生成</div>
        <div class="secret-box">
          <span class="secret-val mono">{{ plainText }}</span>
          <button class="copy-btn" @click="copyToken(plainText)">复制</button>
        </div>
      </div>

      <div class="card">
        <table class="tk-table">
          <thead>
            <tr>
              <th>名称</th>
              <th style="width: 150px">前缀</th>
              <th style="width: 120px">状态</th>
              <th style="width: 170px">到期时间</th>
              <th style="width: 150px">最近使用</th>
              <th style="width: 150px">创建</th>
              <th style="width: 90px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in tokens" :key="row.id" :class="{ revoked: row.revoked }">
              <td class="strong">{{ row.name }}</td>
              <td class="mono muted">{{ row.tokenPrefix }}…</td>
              <td>
                <span v-if="row.revoked" class="tag tag-danger">已吊销</span>
                <span v-else-if="isExpired(row.expiresAt)" class="tag tag-plain">已过期</span>
                <span v-else-if="expiringSoon(row.expiresAt)" class="tag tag-warn">
                  {{ daysLeft(row.expiresAt) }} 天后到期
                </span>
                <span v-else class="tag tag-ok">有效</span>
              </td>
              <td class="num muted">{{ row.expiresAt ? fmt(row.expiresAt) : '永不过期' }}</td>
              <td class="muted">{{ row.lastUsedAt ? fmt(row.lastUsedAt) : '—' }}</td>
              <td class="muted">{{ row.createdAt ? fmt(row.createdAt) : '—' }}</td>
              <td>
                <button v-if="!row.revoked" class="btn-danger" @click="revoke(row)">吊销</button>
              </td>
            </tr>
            <tr v-if="!tokens.length">
              <td colspan="7" class="empty-row">
                还没有 Token，点右上「签发 Token」创建第一个。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ============ AI 访问令牌 ============ -->
    <template v-else>
      <div class="callout">
        <el-icon :size="15"><InfoFilled /></el-icon>
        <div>
          <b>这是给 AI 用的，不是给流水线用的。</b>
          流水线请用「流水线 Token」。这里签发的令牌绑定你的账号，
          能读到什么由你的角色决定，写操作会写审计日志。
        </div>
      </div>

      <div v-if="patPlainText" class="card secret-card">
        <div class="secret-label">新访问令牌（{{ patNewName }}）已生成 · 请立刻复制</div>
        <div class="secret-box">
          <span class="secret-val mono">{{ patPlainText }}</span>
          <button class="copy-btn" @click="copyToken(patPlainText)">复制</button>
        </div>
        <pre class="config-snippet">{
  "mcpServers": {
    "fcloud-ci-hub": {
      "url": "{{ mcpUrl }}",
      "headers": { "Authorization": "Bearer {{ patPlainText }}" }
    }
  }
}</pre>
      </div>

      <div class="card">
        <table class="tk-table">
          <thead>
            <tr>
              <th>名称</th>
              <th style="width: 160px">前缀</th>
              <th style="width: 210px">权限</th>
              <th style="width: 110px">状态</th>
              <th style="width: 160px">到期时间</th>
              <th style="width: 140px">最近使用</th>
              <th style="width: 80px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in pats" :key="row.id" :class="{ revoked: row.revoked }">
              <td class="strong">{{ row.name }}</td>
              <td class="mono muted">{{ row.tokenPrefix }}…</td>
              <td>
                <span
                  v-for="s in row.scopes"
                  :key="s"
                  class="scope"
                  :class="s.replace(':', '-')"
                >{{ scopeLabel(s) }}</span>
              </td>
              <td>
                <span v-if="row.revoked" class="tag tag-danger">已吊销</span>
                <span v-else-if="isExpired(row.expiresAt)" class="tag tag-plain">已过期</span>
                <span v-else-if="expiringSoon(row.expiresAt)" class="tag tag-warn">
                  {{ daysLeft(row.expiresAt) }} 天后到期
                </span>
                <span v-else class="tag tag-ok">有效</span>
              </td>
              <td class="num muted">{{ row.expiresAt ? fmt(row.expiresAt) : '永不过期' }}</td>
              <td class="muted">{{ row.lastUsedAt ? fmt(row.lastUsedAt) : '从未使用' }}</td>
              <td>
                <button v-if="!row.revoked" class="btn-danger" @click="revokePat(row)">
                  吊销
                </button>
              </td>
            </tr>
            <tr v-if="!pats.length">
              <td colspan="7" class="empty-row">
                还没有访问令牌，点右上「新建访问令牌」给 AI 客户端配一个。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 流水线 Token 签发 -->
    <el-dialog v-model="dialog" title="签发 Token" width="460px">
      <el-form label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="ci-pipeline" />
        </el-form-item>
        <el-form-item label="到期时间（可选）">
          <el-date-picker
            v-model="form.expiresAt"
            type="datetime"
            placeholder="不填则永不过期"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <p class="dialog-note">签发后明文只展示一次，请立即复制到 CI 变量中。</p>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">签发</el-button>
      </template>
    </el-dialog>

    <!-- AI 访问令牌签发 -->
    <el-dialog v-model="patDialog" title="新建 AI 访问令牌" width="520px">
      <el-form label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="patForm.name" placeholder="workbuddy-mcp" />
        </el-form-item>
        <el-form-item label="权限">
          <div class="scope-picker">
            <div class="scope-opt">
              <el-checkbox :model-value="true" disabled />
              <div>
                <b>只读</b><span class="scope-code">mcp:read</span>
                <div class="scope-hint">查看应用、模块、配置与差异对比</div>
              </div>
            </div>
            <div class="scope-opt">
              <el-checkbox v-model="patForm.write" />
              <div>
                <b>写入</b><span class="scope-code">mcp:write</span>
                <div class="scope-hint">修改变量值、导入 dotenv、上传文件</div>
              </div>
            </div>
            <div class="scope-opt danger">
              <el-checkbox v-model="patForm.secret" />
              <div>
                <b>查看密钥明文</b><span class="scope-code">mcp:secret</span>
                <div class="scope-hint">
                  高危：密钥会进入 AI 上下文与客户端日志，通常不需要开
                </div>
              </div>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="到期时间">
          <el-date-picker
            v-model="patForm.expiresAt"
            type="datetime"
            placeholder="不填则永不过期"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <p class="dialog-note">
        明文只展示一次。客户端用 <code>Authorization: Bearer &lt;令牌&gt;</code> 访问
        <code>/mcp</code>。
      </p>
      <template #footer>
        <el-button @click="patDialog = false">取消</el-button>
        <el-button type="primary" :loading="patSaving" @click="createPat">新建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { COPY_FAIL_HINT, copyText } from '@/utils/clipboard'
import ProtoIcon from '@/components/ProtoIcon.vue'
import { api } from '@/api/http'
import { useUserStore } from '@/stores/user'
import type { Id } from '@/types/id'

interface TokenRow {
  id: Id
  name: string
  tokenPrefix: string
  expiresAt?: string
  revoked: boolean
  lastUsedAt?: string
  createdAt?: string
}

interface PatRow {
  id: Id
  name: string
  tokenPrefix: string
  scopes: string[]
  expiresAt?: string
  revoked: boolean
  lastUsedAt?: string
  createdAt?: string
}

const userStore = useUserStore()
const isAdmin = computed(() => userStore.isSystemAdmin())
const tab = ref<'pipeline' | 'pat'>('pipeline')
// 非管理员只有「AI 访问令牌」，不依赖 onMounted 的切换，避免首屏闪一下流水线内容
const isPatTab = computed(() => !isAdmin.value || tab.value === 'pat')
const mcpUrl = `${location.origin}/mcp`

// ---------------- 流水线 Token ----------------
const tokens = ref<TokenRow[]>([])
const dialog = ref(false)
const saving = ref(false)
const plainText = ref('')
const newTokenName = ref('')

const form = reactive<{ name: string; expiresAt: Date | null }>({
  name: 'ci-pipeline',
  expiresAt: null
})

// ---------------- AI 访问令牌 ----------------
const pats = ref<PatRow[]>([])
const patDialog = ref(false)
const patSaving = ref(false)
const patPlainText = ref('')
const patNewName = ref('')

function defaultExpiry() {
  return new Date(Date.now() + 90 * 86400000)
}

const patForm = reactive<{
  name: string
  write: boolean
  secret: boolean
  expiresAt: Date | null
}>({
  name: 'workbuddy-mcp',
  write: false,
  secret: false,
  expiresAt: defaultExpiry()
})

function scopeLabel(s: string) {
  const map: Record<string, string> = {
    'mcp:read': '只读',
    'mcp:write': '写入',
    'mcp:secret': '明文'
  }
  return map[s] || s
}

function fmt(v: string) {
  return new Date(v).toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
}

function isExpired(v?: string) {
  return !!v && new Date(v).getTime() < Date.now()
}

function expiringSoon(v?: string) {
  if (!v) return false
  const diff = new Date(v).getTime() - Date.now()
  return diff > 0 && diff < 7 * 24 * 3600 * 1000
}

function daysLeft(v?: string) {
  if (!v) return 0
  return Math.max(1, Math.ceil((new Date(v).getTime() - Date.now()) / 86400000))
}

async function load() {
  if (isAdmin.value) {
    tokens.value = await api.get('/api/console/admin/tokens')
  }
  pats.value = await api.get('/api/console/pat')
}

function openCreate() {
  form.name = 'ci-pipeline'
  form.expiresAt = null
  dialog.value = true
}

async function create() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const res = await api.post<{ token: TokenRow; plainText: string }>(
      '/api/console/admin/tokens',
      {
        name: form.name,
        expiresAt: form.expiresAt ? form.expiresAt.toISOString() : null
      }
    )
    plainText.value = res.plainText
    newTokenName.value = form.name
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openPatCreate() {
  patForm.name = 'workbuddy-mcp'
  patForm.write = false
  patForm.secret = false
  patForm.expiresAt = defaultExpiry()
  patDialog.value = true
}

async function createPat() {
  if (!patForm.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  const scopes = ['mcp:read']
  if (patForm.write) scopes.push('mcp:write')
  if (patForm.secret) scopes.push('mcp:secret')
  patSaving.value = true
  try {
    const res = await api.post<{ token: PatRow; plainText: string }>('/api/console/pat', {
      name: patForm.name,
      scopes,
      expiresAt: patForm.expiresAt ? patForm.expiresAt.toISOString() : null
    })
    patPlainText.value = res.plainText
    patNewName.value = patForm.name
    patDialog.value = false
    await load()
  } finally {
    patSaving.value = false
  }
}

async function copyToken(text: string) {
  if (await copyText(text)) {
    ElMessage.success('已复制')
  } else {
    ElMessage.warning({ message: COPY_FAIL_HINT, duration: 6000 })
  }
}

async function revoke(row: TokenRow) {
  try {
    await ElMessageBox.confirm(
      `吊销后该 Token 立即失效（Redis 缓存同步清除）。确认吊销「${row.name}」？`,
      '吊销 Token',
      { type: 'warning', confirmButtonText: '吊销' }
    )
    await api.del(`/api/console/admin/tokens/${row.id}`)
    ElMessage.success('已吊销')
    await load()
  } catch {
    // 取消
  }
}

async function revokePat(row: PatRow) {
  try {
    await ElMessageBox.confirm(
      `吊销后使用该令牌的 AI 客户端会立即失去访问权限。确认吊销「${row.name}」？`,
      '吊销访问令牌',
      { type: 'warning', confirmButtonText: '吊销' }
    )
    await api.del(`/api/console/pat/${row.id}`)
    ElMessage.success('已吊销')
    await load()
  } catch {
    // 取消
  }
}

onMounted(async () => {
  await userStore.loadMe()
  if (!isAdmin.value) {
    tab.value = 'pat'
  }
  await load()
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

.tk-tabs {
  display: flex;
  gap: 2px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--border);
}

.tk-tabs button {
  height: 34px;
  padding: 0 14px;
  border: 0;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  background: transparent;
  color: var(--text-2);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}

.tk-tabs button:hover {
  color: var(--text);
}

.tk-tabs button.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
  font-weight: 500;
}

.callout {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 14px;
  border-radius: var(--r-sm);
  background: var(--primary-50);
  border: 1px solid var(--primary-100);
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.6;
}

.callout b {
  color: var(--text);
  font-weight: 600;
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
}

.secret-card {
  padding: 14px 16px;
  margin-bottom: 16px;
}

.secret-label {
  font-size: 12px;
  color: var(--text-3);
  margin-bottom: 6px;
}

.secret-box {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #0f172a;
  border-radius: var(--r-sm);
  padding: 10px 12px;
}

.secret-val {
  flex: 1;
  min-width: 0;
  color: #cbd5e1;
  font-size: 12.5px;
  overflow-x: auto;
  white-space: nowrap;
}

.copy-btn {
  flex: 0 0 auto;
  height: 26px;
  padding: 0 10px;
  border-radius: 6px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  background: rgba(255, 255, 255, 0.1);
  color: #cbd5e1;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}

.copy-btn:hover {
  background: rgba(255, 255, 255, 0.18);
}

.config-snippet {
  margin: 10px 0 0;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--surface-3);
  border: 1px solid var(--border);
  color: var(--text-2);
  font-size: 11.5px;
  line-height: 1.55;
  overflow-x: auto;
  white-space: pre;
}

.tk-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.tk-table th {
  height: 36px;
  padding: 0 16px;
  text-align: left;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-2);
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
}

.tk-table td {
  height: 44px;
  padding: 0 16px;
  font-size: 13px;
  border-bottom: 1px solid #f0f1f5;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tk-table tbody tr:hover {
  background: #fbfcfe;
}

.tk-table tr.revoked {
  opacity: 0.6;
}

.tk-table .strong {
  font-weight: 500;
  color: var(--text);
}

.tk-table .muted {
  color: var(--text-3);
}

.tk-table .num {
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
  white-space: nowrap;
}

.tag-plain {
  background: var(--surface-3);
  color: var(--text-2);
}

.tag-ok {
  background: var(--ok-50);
  color: var(--ok);
  border: 1px solid #c9f0dd;
}

.tag-warn {
  background: var(--warn-50);
  color: var(--warn);
  border: 1px solid #fce6b5;
}

.tag-danger {
  background: var(--danger-50);
  color: var(--danger);
  border: 1px solid #f8d4d4;
}

.scope {
  display: inline-flex;
  align-items: center;
  height: 19px;
  padding: 0 6px;
  margin-right: 4px;
  border-radius: 5px;
  font-size: 11.5px;
  line-height: 1;
  white-space: nowrap;
}

.scope.mcp-read {
  background: var(--surface-3);
  color: var(--text-2);
}

.scope.mcp-write {
  background: #eaf3de;
  color: #3b6d11;
}

.scope.mcp-secret {
  background: var(--danger-50);
  color: var(--danger);
}

.btn-danger {
  height: 28px;
  padding: 0 9px;
  border-radius: 6px;
  border: 1px solid #f0c8c8;
  background: #fff;
  color: var(--danger);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}

.btn-danger:hover {
  background: var(--danger-50);
}

.empty-row {
  height: 88px;
  text-align: center;
  color: var(--text-3);
  font-size: 12.5px;
}

.dialog-note {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-3);
}

.dialog-note code {
  background: var(--surface-3);
  padding: 1px 4px;
  border-radius: 4px;
  font-size: 11.5px;
}

.scope-picker {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.scope-opt {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.scope-opt.danger {
  border-color: #f0c8c8;
  background: #fffafa;
}

.scope-opt b {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
}

.scope-code {
  margin-left: 6px;
  font-size: 11px;
  color: var(--text-3);
  font-family: var(--mono, monospace);
}

.scope-hint {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.5;
}
</style>
