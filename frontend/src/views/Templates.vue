<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">模板库</h2>
        <div class="fc-page-subtitle">
          模板定义变量骨架；模块保持关联，按 diff 选择性同步。
        </div>
      </div>
      <button class="btn btn-primary" @click="openCreate">
        <ProtoIcon name="plus" :size="15" />新建模板
      </button>
    </div>

    <div v-if="!loading && !templates.length" class="card fc-empty">
      <el-icon :size="44" color="#d1d5db"><Files /></el-icon>
      <p style="margin-top: 12px">还没有模板</p>
      <p class="muted" style="font-size: 12.5px">
        新建一个变量定义集合，或从已有模块另存为模板。
      </p>
    </div>

    <div v-else class="split">
      <div class="card">
        <div class="card-head">
          <div class="card-title">
            全部模板<span class="n">{{ templates.length }}</span>
          </div>
        </div>
        <div class="card-body list">
          <template v-for="g in grouped" :key="g.name">
            <div class="group-label">
              <span>{{ g.name }}</span>
              <el-dropdown
                v-if="g.name !== '未分组'"
                trigger="click"
                @command="(c: string) => onGroupCommand(c, g.name)"
              >
                <button class="group-more" title="分组管理">
                  <ProtoIcon name="more" :size="13" />
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rename">重命名分组</el-dropdown-item>
                    <el-dropdown-item command="clear">移到未分组</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <button
              v-for="t in g.items"
              :key="t.id"
              class="list-item"
              :class="{ on: isCurrent(t) }"
              @click="select(t)"
            >
              <div class="li-main">
                <div class="t">{{ t.name }}</div>
                <div class="s">{{ t.code }} · {{ t.varCount || 0 }} 变量</div>
              </div>
              <span v-if="Number(t.pendingModules) > 0" class="tag tag-warn">
                {{ t.pendingModules }} 模块待同步
              </span>
            </button>
          </template>
        </div>
      </div>

      <div v-if="current" class="card">
        <div class="card-head detail-head">
          <div class="head-main">
            <div class="detail-title">{{ current.name }}</div>
            <div class="detail-sub mono">
              {{ current.code }} · 更新于 {{ fmt(current.updatedAt) }}
            </div>
          </div>
          <div class="head-actions">
            <button class="btn btn-sm" @click="openSaveAs">从模块另存</button>
            <button class="btn btn-sm btn-primary" @click="openEdit(current)">
              编辑定义
            </button>
            <el-dropdown trigger="click" @command="onCommand">
              <button class="icon-btn"><ProtoIcon name="more" :size="15" /></button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="delete">删除模板</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>

        <table class="def-table">
          <thead>
            <tr>
              <th style="width: 44px">#</th>
              <th>变量 key</th>
              <th style="width: 84px">类型</th>
              <th style="width: 72px">必填</th>
              <th style="width: 180px">默认值</th>
              <th style="width: 200px">备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(v, i) in detailVars" :key="v.varKey || i">
              <td class="muted num">{{ i + 1 }}</td>
              <td class="mono strong">{{ v.varKey }}</td>
              <td>
                <span v-if="v.valueType === 'ENCRYPTED'" class="tag tag-lock">
                  <el-icon :size="10"><Lock /></el-icon>加密
                </span>
                <span v-else class="tag tag-plain">明文</span>
              </td>
              <td>
                <span v-if="v.isRequired" class="tag tag-plain">必填</span>
                <span v-else class="muted">—</span>
              </td>
              <td class="mono">
                <span v-if="v.valueType === 'ENCRYPTED'" class="muted small">不落库</span>
                <span v-else-if="v.defaultValue">{{ v.defaultValue }}</span>
                <span v-else class="muted">—</span>
              </td>
              <td class="muted">{{ v.remark || '—' }}</td>
            </tr>
            <tr v-if="!detailVars.length">
              <td colspan="6" class="empty-row">
                这个模板还没有变量定义，点右上「编辑定义」添加。
              </td>
            </tr>
          </tbody>
        </table>

        <div class="card-foot">
          <span>实例化后：明文默认值写入全局值，加密类型默认值不落库</span>
          <button class="btn btn-sm" @click="openInstantiate(current)">
            用此模板建模块
          </button>
        </div>
      </div>
    </div>

    <!-- 模板编辑（右侧抽屉，DESIGN §2.7） -->
    <el-drawer
      v-model="drawer"
      :title="form.id ? '编辑模板' : '新建模板'"
      size="760px"
      :before-close="beforeClose"
    >
      <section class="tsec">
        <div class="tsec-head">
          <div class="tsec-title">基本信息</div>
        </div>

        <div class="fld-grid">
          <label class="fld">
            <span class="fld-lb">
              Code<span class="req">*</span>
              <span v-if="form.id" class="fld-opt">创建后不可修改</span>
            </span>
            <el-input
              v-model="form.code"
              :disabled="!!form.id"
              placeholder="db-config-standard"
            />
          </label>
          <label class="fld">
            <span class="fld-lb">名称<span class="req">*</span></span>
            <el-input v-model="form.name" placeholder="数据库配置标准" />
          </label>
        </div>

        <label class="fld">
          <span class="fld-lb">
            分组
            <span class="fld-opt">决定在模板库左侧归到哪一组</span>
          </span>
          <el-select
            v-model="form.groupName"
            filterable
            allow-create
            clearable
            default-first-option
            placeholder="选择已有分组，或直接输入新名称"
            style="width: 100%"
          >
            <el-option v-for="g in groupOptions" :key="g" :label="g" :value="g" />
          </el-select>
        </label>

        <label class="fld">
          <span class="fld-lb">描述<span class="fld-opt">选填</span></span>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="这套定义适用什么场景、谁来维护"
          />
        </label>
      </section>

      <section class="tsec">
        <div class="tsec-head">
          <div class="tsec-title">
            变量定义
            <span class="cnt">{{ form.vars.length }}</span>
          </div>
          <div class="tsec-actions">
            <button class="btn btn-sm" @click="pasteOpen = true">
              <ProtoIcon name="upload" :size="14" />批量粘贴
            </button>
            <button class="btn btn-sm btn-primary" @click="addVar">
              <ProtoIcon name="plus" :size="14" />添加变量
            </button>
          </div>
        </div>

        <div v-if="form.vars.length" ref="vtableEl" class="vtable">
          <div class="vrow vhead">
            <span></span>
            <span class="c-idx">#</span>
            <span>变量 key</span>
            <span>类型</span>
            <span>默认值</span>
            <span>必填</span>
            <span>备注</span>
            <span></span>
          </div>

          <div
            v-for="(v, i) in form.vars"
            :key="v._k"
            class="vrow"
            :class="{ dragging: dragIndex === i, bad: isDup(v) }"
            :draggable="dragArmed === i"
            @dragstart="onDragStart(i)"
            @dragover.prevent
            @drop="onDrop(i)"
            @dragend="onDragEnd"
          >
            <span
              class="grip"
              title="拖动调整顺序（决定模块矩阵里的行序）"
              @mousedown="dragArmed = i"
              @mouseup="dragArmed = null"
            >
              <ProtoIcon name="grip" :size="14" />
            </span>
            <span class="c-idx">{{ i + 1 }}</span>
            <el-input
              v-model="v.varKey"
              class="k-input"
              placeholder="DB_HOST"
              :title="isDup(v) ? '与其他变量 key 重复' : ''"
              @blur="v.varKey = v.varKey.trim()"
            />
            <div class="seg">
              <button
                type="button"
                :class="{ on: v.valueType === 'PLAIN' }"
                @click="setType(v, 'PLAIN')"
              >
                明文
              </button>
              <button
                type="button"
                :class="{ on: v.valueType === 'ENCRYPTED' }"
                @click="setType(v, 'ENCRYPTED')"
              >
                加密
              </button>
            </div>
            <el-input
              v-model="v.defaultValue"
              :disabled="v.valueType === 'ENCRYPTED'"
              :placeholder="v.valueType === 'ENCRYPTED' ? '不落库' : '可留空'"
            />
            <button
              type="button"
              class="req-pill"
              :class="{ on: v.isRequired }"
              title="必填：模块里没配这个变量时，会进入工作台待办"
              @click="v.isRequired = !v.isRequired"
            >
              {{ v.isRequired ? '必填' : '选填' }}
            </button>
            <el-input v-model="v.remark" placeholder="用途说明" />
            <button type="button" class="row-del" title="删除这一行" @click="removeVar(i)">
              <ProtoIcon name="x" :size="14" />
            </button>
          </div>
        </div>

        <div v-else class="vempty">
          <ProtoIcon name="layers" :size="20" style="color: #c3c9d4" />
          <div class="ve-t">还没有变量定义</div>
          <div class="ve-s">点「添加变量」逐个定义，或「批量粘贴」一次导入 .env</div>
        </div>

        <div class="vhint">
          <ProtoIcon name="lock" :size="12" style="color: #9aa3b2; margin-top: 1px" />
          <span>
            加密变量不保存默认值，实例化后由各模块单独填写；必填变量在模块里为空时会进入工作台待办。
          </span>
        </div>
      </section>

      <template #footer>
        <div class="drawer-foot">
          <span class="foot-note">
            {{ form.vars.length }} 个变量 · 实例化后明文默认值写入全局值
          </span>
          <div class="foot-actions">
            <button class="btn" @click="requestClose">取消</button>
            <button class="btn btn-primary" :disabled="saving" @click="save">
              {{ saving ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 批量粘贴变量 -->
    <el-dialog v-model="pasteOpen" title="批量粘贴变量" width="540px">
      <p class="dialog-note" style="margin: 0 0 10px">
        每行一个 <code class="inline-code">KEY=默认值</code>，只写 KEY 也可以。key 含
        PASSWORD / SECRET / TOKEN / KEY 时自动标记为加密，默认值不写入模板。
      </p>
      <el-input
        v-model="pasteText"
        type="textarea"
        :rows="9"
        placeholder="DB_HOST=10.0.2.9&#10;DB_PORT=5432&#10;DB_PASSWORD="
      />
      <template #footer>
        <button class="btn" @click="pasteOpen = false">取消</button>
        <button class="btn btn-primary" @click="doPaste">导入到列表</button>
      </template>
    </el-dialog>

    <!-- 从模块另存为模板 -->
    <el-dialog v-model="saveAsDialog" title="从模块另存为模板" width="480px">
      <el-form label-position="top">
        <el-form-item label="来源应用" required>
          <el-select
            v-model="saveAsForm.applicationId"
            filterable
            style="width: 100%"
            @change="onSaveAsAppChange"
          >
            <el-option
              v-for="a in applications"
              :key="a.id"
              :label="`${a.name} (${a.code})`"
              :value="a.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="来源模块" required>
          <el-select v-model="saveAsForm.moduleId" filterable style="width: 100%">
            <el-option
              v-for="m in saveAsModules"
              :key="m.id"
              :label="`${m.name} (${m.code})`"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="模板 Code" required>
          <el-input v-model="saveAsForm.code" placeholder="db-config-standard" />
        </el-form-item>
        <el-form-item label="模板名称" required>
          <el-input v-model="saveAsForm.name" placeholder="数据库配置标准" />
        </el-form-item>
        <el-form-item label="分组">
          <el-select
            v-model="saveAsForm.groupName"
            filterable
            allow-create
            clearable
            default-first-option
            placeholder="数据库 / 消息 / 缓存…"
            style="width: 100%"
          >
            <el-option v-for="g in groupOptions" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="dialog-note">
        复制模块当前的变量定义（key/类型/备注/必填）；加密类型的全局值不会进入模板。
      </p>
      <template #footer>
        <el-button @click="saveAsDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSaveAs">另存为模板</el-button>
      </template>
    </el-dialog>

    <!-- 实例化 -->
    <el-dialog v-model="instDialog" title="用此模板建模块" width="460px">
      <el-form label-position="top">
        <el-form-item label="目标应用" required>
          <el-select v-model="instForm.applicationId" filterable style="width: 100%">
            <el-option
              v-for="a in applications"
              :key="a.id"
              :label="`${a.name} (${a.code})`"
              :value="a.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="模块 Code" required>
          <el-input v-model="instForm.code" placeholder="db-config" />
        </el-form-item>
        <el-form-item label="模块名称">
          <el-input v-model="instForm.name" />
        </el-form-item>
        <el-form-item label="分组">
          <el-select
            v-model="instForm.groupName"
            filterable
            allow-create
            clearable
            default-first-option
            placeholder="选择已有分组，或直接输入新名称"
            style="width: 100%"
          >
            <el-option v-for="g in moduleGroups" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定环境" required>
          <el-select
            v-model="instForm.environmentIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            style="width: 100%"
            placeholder="选择模块要覆盖的环境"
          >
            <el-option
              v-for="e in envs"
              :key="e.id"
              :label="`${e.name} · ${e.code}`"
              :value="e.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="dialog-note">
        明文默认值会写入模块变量的全局值；加密类型的默认值不落库。
      </p>
      <template #footer>
        <el-button @click="instDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doInstantiate">
          实例化
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
import ProtoIcon from '@/components/ProtoIcon.vue'
import { api } from '@/api/http'
import { envApi } from '@/api'
import { templateApi, type TemplateItem, type TemplateVar } from '@/api'
import type { Id } from '@/types/id'

type VarRow = TemplateVar & { _k: string }

interface BoardModule {
  id: Id
  code: string
  name: string
  groupName?: string
}

interface BoardAppLite {
  id: Id
  code: string
  name: string
  privateModules: BoardModule[]
}

const router = useRouter()
const loading = ref(true)
const templates = ref<TemplateItem[]>([])
const applications = ref<BoardAppLite[]>([])
const currentId = ref<Id | null>(null)
const detailVars = ref<TemplateVar[]>([])

const drawer = ref(false)
const pasteOpen = ref(false)
const pasteText = ref('')
const vtableEl = ref<HTMLElement | null>(null)
const dragIndex = ref<number | null>(null)
const dragArmed = ref<number | null>(null)
const snapshot = ref('')
const instDialog = ref(false)
const saveAsDialog = ref(false)
const saving = ref(false)

const form = reactive<{
  id?: Id
  code: string
  name: string
  description: string
  groupName: string
  vars: VarRow[]
}>({ code: '', name: '', description: '', groupName: '', vars: [] })

const instForm = reactive<{
  templateId?: Id
  applicationId?: Id
  code: string
  name: string
  groupName: string
  environmentIds: Id[]
}>({ code: '', name: '', groupName: '', environmentIds: [] })

const envs = ref<Array<{ id: Id; code: string; name: string }>>([])

const saveAsForm = reactive<{
  applicationId?: Id
  moduleId?: Id
  code: string
  name: string
  groupName: string
}>({ code: '', name: '', groupName: '' })

const current = computed(
  () => templates.value.find((t) => t.id === currentId.value) || null
)

const groupOptions = ['数据库', '消息', '缓存', '基础', '业务']

const grouped = computed(() => {
  const order: string[] = []
  const map = new Map<string, TemplateItem[]>()
  for (const t of templates.value) {
    const g = t.groupName || '未分组'
    if (!map.has(g)) {
      map.set(g, [])
      order.push(g)
    }
    map.get(g)!.push(t)
  }
  return order.map((name) => ({ name, items: map.get(name)! }))
})

const moduleGroups = computed(() => {
  const set = new Set<string>()
  for (const a of applications.value) {
    for (const m of a.privateModules || []) {
      if (m.groupName) set.add(m.groupName)
    }
  }
  return Array.from(set)
})

const saveAsModules = computed(
  () =>
    applications.value.find((a) => a.id === saveAsForm.applicationId)
      ?.privateModules || []
)

function isCurrent(t: TemplateItem) {
  return t.id === currentId.value
}

function fmt(v?: string) {
  return v
    ? new Date(v).toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
    : '—'
}

async function load() {
  loading.value = true
  try {
    templates.value = await templateApi.list()
    if (!currentId.value && templates.value.length) {
      currentId.value = templates.value[0].id
    }
    if (currentId.value && !templates.value.some((t) => t.id === currentId.value)) {
      currentId.value = templates.value[0]?.id ?? null
    }
    await loadDetail()
  } finally {
    loading.value = false
  }
}

async function loadDetail() {
  if (!currentId.value) {
    detailVars.value = []
    return
  }
  const d = await templateApi.detail(currentId.value)
  detailVars.value = d.vars || []
}

async function select(t: TemplateItem) {
  if (t.id === currentId.value) return
  currentId.value = t.id
  detailVars.value = []
  await loadDetail()
}

async function loadApplications() {
  try {
    const board = await api.get<{ applications: BoardAppLite[] }>(
      '/api/console/applications/board'
    )
    applications.value = board.applications || []
  } catch {
    applications.value = []
  }
}

function openCreate() {
  form.id = undefined
  form.code = ''
  form.name = ''
  form.description = ''
  form.groupName = ''
  form.vars = []
  captureSnapshot()
  drawer.value = true
}

async function openEdit(row: TemplateItem) {
  const d = await templateApi.detail(row.id)
  form.id = d.id
  form.code = d.code
  form.name = d.name
  form.description = d.description || ''
  form.groupName = d.groupName || ''
  form.vars = (d.vars || []).map((v) => ({ ...v, _k: uid() }))
  captureSnapshot()
  drawer.value = true
}

function uid() {
  return Math.random().toString(36).slice(2, 9)
}

function blankVar(): VarRow {
  return {
    _k: uid(),
    varKey: '',
    valueType: 'PLAIN',
    remark: '',
    defaultValue: '',
    isRequired: false,
    sortOrder: form.vars.length
  }
}

async function addVar() {
  form.vars.push(blankVar())
  await nextTick()
  const inputs = vtableEl.value?.querySelectorAll<HTMLInputElement>('.k-input input')
  const last = inputs?.[inputs.length - 1]
  if (last) {
    last.scrollIntoView({ block: 'nearest' })
    last.focus()
  }
}

function removeVar(i: number) {
  form.vars.splice(i, 1)
}

function setType(v: VarRow, t: 'PLAIN' | 'ENCRYPTED') {
  if (v.valueType === t) return
  v.valueType = t
  // 加密变量不写默认值，切换时清空，避免界面上留着会被丢弃的值
  if (t === 'ENCRYPTED') v.defaultValue = ''
}

const dupKeys = computed(() => {
  const seen = new Map<string, number>()
  for (const v of form.vars) {
    const k = v.varKey.trim().toUpperCase()
    if (!k) continue
    seen.set(k, (seen.get(k) || 0) + 1)
  }
  return new Set(
    Array.from(seen.entries())
      .filter(([, n]) => n > 1)
      .map(([k]) => k)
  )
})

function isDup(v: VarRow) {
  const k = v.varKey.trim().toUpperCase()
  return !!k && dupKeys.value.has(k)
}

function onDragStart(i: number) {
  dragIndex.value = i
}

function onDrop(i: number) {
  const from = dragIndex.value
  dragIndex.value = null
  dragArmed.value = null
  if (from === null || from === i) return
  const [row] = form.vars.splice(from, 1)
  form.vars.splice(i, 0, row)
}

function onDragEnd() {
  dragIndex.value = null
  dragArmed.value = null
}

function captureSnapshot() {
  snapshot.value = JSON.stringify(form)
}

const isDirty = computed(() => JSON.stringify(form) !== snapshot.value)

function closeNow() {
  drawer.value = false
}

function requestClose() {
  if (saving.value) return
  if (!isDirty.value) {
    closeNow()
    return
  }
  ElMessageBox.confirm(
    '变量定义或基本信息有未保存的修改，关闭后不会保留。',
    '放弃未保存的修改？',
    {
      type: 'warning',
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑'
    }
  )
    .then(closeNow)
    .catch(() => {})
}

function beforeClose(done: () => void) {
  if (saving.value) return
  if (!isDirty.value) {
    done()
    return
  }
  ElMessageBox.confirm(
    '变量定义或基本信息有未保存的修改，关闭后不会保留。',
    '放弃未保存的修改？',
    {
      type: 'warning',
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑'
    }
  )
    .then(() => done())
    .catch(() => {})
}

function doPaste() {
  const lines = pasteText.value
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'))

  if (!lines.length) {
    ElMessage.warning('没有可导入的内容')
    return
  }

  const existing = new Set(
    form.vars.map((v) => v.varKey.trim()).filter(Boolean)
  )
  let added = 0
  let dup = 0
  let bad = 0

  for (const line of lines) {
    const eq = line.indexOf('=')
    const key = (eq === -1 ? line : line.slice(0, eq)).trim()
    const value = eq === -1 ? '' : line.slice(eq + 1).trim()
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) {
      bad += 1
      continue
    }
    if (existing.has(key)) {
      dup += 1
      continue
    }
    const encrypted = /PASSWORD|SECRET|TOKEN|PRIVATE|_KEY$/i.test(key)
    form.vars.push({
      _k: uid(),
      varKey: key,
      valueType: encrypted ? 'ENCRYPTED' : 'PLAIN',
      remark: '',
      defaultValue: encrypted ? '' : value,
      isRequired: false,
      sortOrder: form.vars.length
    })
    existing.add(key)
    added += 1
  }

  const tail = [
    dup ? `跳过 ${dup} 个重复 key` : '',
    bad ? `忽略 ${bad} 行格式不对` : ''
  ]
    .filter(Boolean)
    .join('，')

  if (added) {
    ElMessage.success(`已导入 ${added} 个变量${tail ? '，' + tail : ''}`)
    pasteOpen.value = false
    pasteText.value = ''
  } else {
    ElMessage.warning(tail || '没有可导入的内容')
  }
}

async function save() {
  if (!form.code || !form.name) {
    ElMessage.warning('Code 与名称必填')
    return
  }
  const blankAt = form.vars.findIndex((v) => !v.varKey.trim())
  if (blankAt >= 0) {
    ElMessage.warning(`第 ${blankAt + 1} 行还没填变量 key`)
    return
  }
  form.vars.forEach((v) => {
    v.varKey = v.varKey.trim()
  })
  if (dupKeys.value.size) {
    ElMessage.warning(`变量 key 重复：${Array.from(dupKeys.value).join('、')}`)
    return
  }
  saving.value = true
  try {
    form.vars.forEach((v, i) => {
      v.sortOrder = i
    })
    const payload = {
      code: form.code,
      name: form.name,
      description: form.description,
      groupName: form.groupName || null,
      vars: form.vars.map(({ _k, ...rest }) => ({ ...rest }))
    }
    if (form.id) {
      await templateApi.update(form.id, payload)
    } else {
      const created = await templateApi.create(payload)
      currentId.value = (created as { id?: Id }).id ?? currentId.value
    }
    ElMessage.success('已保存')
    drawer.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onGroupCommand(cmd: string, group: string) {
  try {
    if (cmd === 'rename') {
      const { value } = await ElMessageBox.prompt(
        `重命名分组「${group}」，组内 ${groupCount(group)} 个模板会一起改。`,
        '重命名分组',
        { inputValue: group, confirmButtonText: '重命名' }
      )
      const to = (value || '').trim()
      if (!to || to === group) return
      await templateApi.renameGroup(group, to)
    } else if (cmd === 'clear') {
      await ElMessageBox.confirm(
        `把「${group}」下的 ${groupCount(group)} 个模板移到「未分组」？模板本身不会删除。`,
        '移到未分组',
        { type: 'warning', confirmButtonText: '移出分组' }
      )
      await templateApi.renameGroup(group, null)
    }
    await load()
  } catch {
    // 取消
  }
}

function groupCount(group: string) {
  return templates.value.filter((t) => (t.groupName || '未分组') === group).length
}

function onCommand(cmd: string) {
  if (cmd === 'delete' && current.value) void remove(current.value)
}

async function remove(row: TemplateItem) {
  try {
    await ElMessageBox.confirm(
      `删除模板「${row.name}」？关联模块的 template_id 会被置空（模块与其变量保留）。`,
      '删除模板',
      { type: 'warning', confirmButtonText: '删除' }
    )
    await templateApi.remove(row.id)
    ElMessage.success('已删除')
    if (currentId.value === row.id) currentId.value = null
    await load()
  } catch {
    // 取消
  }
}

function openInstantiate(row: TemplateItem) {
  instForm.templateId = row.id
  instForm.applicationId = applications.value[0]?.id
  instForm.code = row.code
  instForm.name = row.name
  instForm.groupName = ''
  instForm.environmentIds = envs.value.map((e) => e.id)
  instDialog.value = true
}

async function doInstantiate() {
  if (!instForm.applicationId || !instForm.code) {
    ElMessage.warning('请选择应用并填写模块 code')
    return
  }
  if (!instForm.environmentIds.length) {
    ElMessage.warning('至少绑定一个环境')
    return
  }
  saving.value = true
  try {
    const mod = await templateApi.instantiate(instForm.templateId!, {
      applicationId: instForm.applicationId,
      code: instForm.code,
      name: instForm.name || instForm.code,
      groupName: instForm.groupName || undefined,
      environmentIds: instForm.environmentIds
    })
    ElMessage.success('已实例化')
    instDialog.value = false
    if (mod && (mod as { id?: Id }).id) {
      router.push(`/modules/${(mod as { id: Id }).id}`)
    }
  } finally {
    saving.value = false
  }
}

function openSaveAs() {
  saveAsForm.applicationId = applications.value[0]?.id
  saveAsForm.moduleId = saveAsModules.value[0]?.id
  saveAsForm.code = ''
  saveAsForm.name = ''
  saveAsForm.groupName = current.value?.groupName || ''
  if (current.value) {
    saveAsForm.code = `${current.value.code}-copy`
    saveAsForm.name = `${current.value.name}（副本）`
  }
  saveAsDialog.value = true
}

function onSaveAsAppChange() {
  saveAsForm.moduleId = saveAsModules.value[0]?.id
}

async function doSaveAs() {
  if (!saveAsForm.moduleId || !saveAsForm.code || !saveAsForm.name) {
    ElMessage.warning('请选择源模块并填写模板 code 与名称')
    return
  }
  saving.value = true
  try {
    const t = await templateApi.saveAs(saveAsForm.moduleId, {
      code: saveAsForm.code,
      name: saveAsForm.name,
      groupName: saveAsForm.groupName || undefined
    })
    ElMessage.success('已另存为模板')
    saveAsDialog.value = false
    currentId.value = (t as { id?: Id }).id ?? currentId.value
    await load()
  } catch {
    // 错误已由拦截器提示
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), loadApplications()])
  try {
    envs.value = await envApi.list()
  } catch {
    envs.value = []
  }
})
</script>

<style scoped>
.split {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  align-items: start;
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
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
  display: flex;
  align-items: center;
  gap: 7px;
}

.card-title .n {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-3);
  background: var(--surface-3);
  border-radius: 5px;
  padding: 0 6px;
  line-height: 17px;
}

.list {
  padding: 6px;
}

.list-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  width: 100%;
  padding: 11px 12px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
}

.list-item:hover {
  background: var(--surface-3);
}

.list-item.on {
  background: var(--primary-50);
  box-shadow: inset 2px 0 0 var(--primary);
}

.group-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 10px 12px 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.07em;
  color: var(--text-3);
}

.group-more {
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--text-3);
  display: grid;
  place-items: center;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity 0.14s;
}

.group-label:hover .group-more,
.group-more:hover {
  opacity: 1;
}

.group-more:hover {
  background: var(--surface-3);
  color: var(--text);
}

.li-main {
  flex: 1;
  min-width: 0;
}

.list-item .t {
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
}

.list-item .s {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 2px;
  font-family: var(--mono);
}

.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 20px;
  padding: 0 6px;
  border-radius: 5px;
  font-size: 11.5px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  flex: 0 0 auto;
}

.tag-plain {
  background: var(--surface-3);
  color: var(--text-2);
}

.tag-lock {
  background: #fdf4ff;
  color: #86198f;
  border: 1px solid #f5d0fe;
}

.tag-warn {
  background: var(--warn-50);
  color: var(--warn);
  border: 1px solid #fce6b5;
}

.detail-head {
  align-items: flex-start;
}

.head-main {
  min-width: 0;
}

.detail-title {
  font-size: 14px;
  font-weight: 600;
}

.detail-sub {
  font-size: 11.5px;
  color: var(--text-3);
  margin-top: 2px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.def-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.def-table th {
  height: 36px;
  padding: 0 12px;
  text-align: left;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-2);
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
}

.def-table td {
  height: 40px;
  padding: 0 12px;
  font-size: 13px;
  border-bottom: 1px solid #f0f1f5;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.def-table tbody tr:hover {
  background: #fbfcfe;
}

.def-table .strong {
  font-weight: 500;
  color: var(--text);
}

.def-table .muted {
  color: var(--text-3);
}

.def-table .small {
  font-size: 11.5px;
}

.def-table .num {
  font-variant-numeric: tabular-nums;
}

.empty-row {
  text-align: center;
  color: var(--text-3);
  font-size: 12.5px;
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-top: 1px solid var(--border);
  font-size: 12.5px;
  color: var(--text-3);
}

.dialog-note {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.6;
}

.tsec + .tsec {
  margin-top: 20px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}

.tsec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.tsec-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.cnt {
  display: inline-grid;
  place-items: center;
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: var(--surface-3);
  color: var(--text-2);
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.tsec-actions {
  display: flex;
  gap: 8px;
}

.fld {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 14px;
}

.fld:last-child {
  margin-bottom: 0;
}

.fld-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
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

.req {
  color: var(--danger);
  font-style: normal;
}

.vtable {
  border: 1px solid var(--border);
  border-radius: var(--r);
  overflow: hidden;
}

.vrow {
  display: grid;
  grid-template-columns:
    16px 20px minmax(108px, 1.25fr) 86px minmax(94px, 1fr) 62px
    minmax(86px, 1fr) 26px;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-bottom: 1px solid #f0f1f5;
}

.vrow:last-child {
  border-bottom: 0;
}

.vhead {
  padding: 0 8px;
  height: 34px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-2);
}

.vtable > .vrow:not(.vhead):hover {
  background: #fbfcfe;
}

.vrow.dragging {
  background: var(--primary-50);
  outline: 1px dashed var(--primary-100);
  outline-offset: -1px;
}

.c-idx {
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.grip {
  display: inline-flex;
  color: #cdd3dd;
  cursor: grab;
}

.vtable > .vrow:hover .grip {
  color: var(--text-3);
}

.vrow .el-input {
  width: 100%;
}

.vrow :deep(.el-input__wrapper) {
  padding: 0 9px;
}

.vrow :deep(.el-input__inner) {
  font-size: 12.5px;
}

.k-input :deep(.el-input__inner) {
  font-family: var(--mono);
  font-size: 12.5px;
  letter-spacing: 0.01em;
}

.vrow.bad :deep(.k-input .el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--danger) inset !important;
}

.vrow.bad :deep(.k-input .el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px var(--danger) inset,
    0 0 0 3px rgba(220, 38, 38, 0.12) !important;
}

.vrow.bad :deep(.k-input .el-input__inner) {
  color: var(--danger);
  font-weight: 500;
}

.vrow.bad .c-idx {
  color: var(--danger);
  font-weight: 600;
}

.seg {
  display: flex;
  gap: 2px;
  height: 32px;
  padding: 2px;
  border: 1px solid var(--border-2);
  border-radius: var(--r-sm);
  background: var(--surface-3);
}

.seg button {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  font-family: inherit;
  font-size: 12px;
  color: var(--text-3);
  cursor: pointer;
  transition: 0.12s;
}

.seg button:hover {
  color: var(--text);
}

.seg button.on {
  background: var(--surface);
  color: var(--primary);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.1);
}

.req-pill {
  height: 26px;
  padding: 0 6px;
  border: 1px dashed var(--border-2);
  border-radius: 5px;
  background: transparent;
  color: var(--text-3);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: 0.12s;
  white-space: nowrap;
}

.req-pill:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.req-pill.on {
  border: 1px solid var(--primary-100);
  background: var(--primary-50);
  color: var(--primary-700);
  font-weight: 600;
}

.row-del {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #b6bdc9;
  cursor: pointer;
  transition: 0.12s;
}

.vtable > .vrow:hover .row-del {
  color: var(--text-3);
}

.row-del:hover {
  background: var(--danger-50);
  color: var(--danger);
}

.vempty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  padding: 26px 12px;
  border: 1px dashed var(--border-2);
  border-radius: var(--r);
  background: var(--surface-2);
  text-align: center;
}

.ve-t {
  margin-top: 3px;
  font-size: 13px;
  color: var(--text-2);
}

.ve-s {
  font-size: 11.5px;
  color: var(--text-3);
}

.vhint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 9px;
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--text-3);
}

.inline-code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--surface-3);
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--text-2);
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

@media (max-width: 980px) {
  .split {
    grid-template-columns: 1fr;
  }
}
</style>
