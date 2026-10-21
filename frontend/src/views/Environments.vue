<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">环境管理</h2>
        <div class="fc-page-subtitle">
          父环境构成继承链；变量未覆盖时沿父链回溯取值。
        </div>
      </div>
      <div class="ph-actions">
        <button class="btn btn-primary" @click="openCreate">
          <ProtoIcon name="plus" :size="15" />新建环境
        </button>
      </div>
    </div>

    <div class="card chain-card">
      <div class="card-head">
        <div class="card-title">继承链</div>
        <span class="muted" style="font-size: 12px">成环配置将被拒绝保存</span>
      </div>
      <div class="card-body">
        <div v-for="(chain, i) in chains" :key="i" class="chain">
          <template v-for="(e, j) in chain" :key="e.id">
            <div v-if="j > 0" class="chain-arrow">
              <ProtoIcon name="arrow-r" :size="14" />
            </div>
            <div class="chain-node">
              <span class="dot" :style="{ background: envColor(e) }" />
              <b class="mono node-code">{{ e.code }}</b>
              <span class="muted">{{ e.name }}</span>
              <span v-if="e.isDefault" class="tag tag-danger">默认</span>
            </div>
          </template>
        </div>
        <div v-if="!environments.length" class="muted" style="font-size: 12.5px">
          还没有环境，先新建一个（例如 dev）。
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">
          全部环境<span class="n">{{ environments.length }}</span>
        </div>
      </div>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th style="width: 44px"></th>
              <th>环境</th>
              <th style="width: 140px">code</th>
              <th style="width: 160px">父环境</th>
              <th style="width: 100px">绑定模块</th>
              <th style="width: 90px">默认</th>
              <th style="width: 120px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(row, index) in environments"
              :key="row.id"
              draggable="true"
              :class="{ dragging: dragIndex === index }"
              @dragstart="dragIndex = index"
              @dragover.prevent
              @drop="onDrop(index)"
              @dragend="dragIndex = null"
            >
              <td>
                <span class="grip"><ProtoIcon name="grip" :size="15" /></span>
              </td>
              <td><b>{{ row.name }}</b></td>
              <td class="mono muted">{{ row.code }}</td>
              <td class="mono muted">{{ row.parentEnvId ? codeOf(row.parentEnvId) : '—' }}</td>
              <td class="num">{{ row.moduleCount ?? 0 }}</td>
              <td>
                <span v-if="row.isDefault" class="tag tag-danger">默认</span>
                <span v-else class="muted">—</span>
              </td>
              <td>
                <button class="btn btn-ghost btn-sm" @click="openEdit(row)">编辑</button>
                <el-dropdown trigger="click" @command="(c: string) => onCommand(c, row)">
                  <button class="icon-btn"><ProtoIcon name="more" :size="15" /></button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="default" :disabled="row.isDefault">
                        设为默认
                      </el-dropdown-item>
                      <el-dropdown-item command="delete" divided>删除环境</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </td>
            </tr>
            <tr v-if="!environments.length">
              <td colspan="7" class="empty-row">还没有环境，点右上「新建环境」。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <el-drawer v-model="drawer" :title="form.id ? '编辑环境' : '新建环境'" size="480px">
      <el-form :model="form" label-position="top">
        <el-form-item label="Code" required>
          <el-input v-model="form.code" :disabled="!!form.id" placeholder="prod" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="生产" />
        </el-form-item>
        <el-form-item label="颜色">
          <el-color-picker v-model="form.color" />
        </el-form-item>
        <el-form-item label="父环境（继承链）">
          <el-select v-model="form.parentEnvId" clearable style="width: 100%" placeholder="不继承">
            <el-option
              v-for="e in parentCandidates"
              :key="e.id"
              :label="e.name"
              :value="e.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.isDefault">设为默认环境</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <button class="btn" @click="drawer = false">取消</button>
        <button class="btn btn-primary" :disabled="saving" @click="save">保存</button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { envApi, type Env } from '@/api'
import ProtoIcon from '@/components/ProtoIcon.vue'
import type { Id } from '@/types/id'

const ENV_COLORS: Record<string, string> = {
  dev: '#0284c7',
  test: '#7c3aed',
  staging: '#d97706',
  prod: '#dc2626'
}

const environments = ref<Env[]>([])
const drawer = ref(false)
const saving = ref(false)
const dragIndex = ref<number | null>(null)

const form = reactive<{
  id?: Id
  code: string
  name: string
  color: string
  parentEnvId: Id | null
  isDefault: boolean
}>({ code: '', name: '', color: '#2563eb', parentEnvId: null, isDefault: false })

const parentCandidates = computed(() => environments.value.filter((e) => e.id !== form.id))

/** 按 parent_env_id 串成继承链；多个根就画多条链。 */
const chains = computed(() => {
  const byId = new Map(environments.value.map((e) => [e.id, e]))
  const children = new Map<Id, Env[]>()
  const roots: Env[] = []
  for (const e of environments.value) {
    if (e.parentEnvId && byId.has(e.parentEnvId)) {
      if (!children.has(e.parentEnvId)) children.set(e.parentEnvId, [])
      children.get(e.parentEnvId)!.push(e)
    } else {
      roots.push(e)
    }
  }
  const out: Env[][] = []
  for (const root of roots) {
    const chain: Env[] = [root]
    let cur = root
    const seen = new Set<Id>([root.id])
    while (children.get(cur.id)?.length) {
      const next = children.get(cur.id)![0]
      if (seen.has(next.id)) break
      seen.add(next.id)
      chain.push(next)
      cur = next
    }
    out.push(chain)
  }
  return out
})

function envColor(e: Env) {
  return e.color || ENV_COLORS[e.code] || '#2563eb'
}

function codeOf(id?: Id | null) {
  return environments.value.find((e) => e.id === id)?.code || `#${id}`
}

async function load() {
  environments.value = await envApi.list()
}

function openCreate() {
  form.id = undefined
  form.code = ''
  form.name = ''
  form.color = '#2563eb'
  form.parentEnvId = null
  form.isDefault = false
  drawer.value = true
}

function openEdit(row: Env) {
  form.id = row.id
  form.code = row.code
  form.name = row.name
  form.color = row.color || '#2563eb'
  form.parentEnvId = row.parentEnvId ?? null
  form.isDefault = row.isDefault
  drawer.value = true
}

async function save() {
  if (!form.code || !form.name) {
    ElMessage.warning('Code 与名称必填')
    return
  }
  saving.value = true
  try {
    const payload = {
      code: form.code,
      name: form.name,
      color: form.color,
      parentEnvId: form.parentEnvId,
      isDefault: form.isDefault
    }
    if (form.id) {
      await envApi.update(form.id, payload)
    } else {
      await envApi.create(payload)
    }
    ElMessage.success('已保存')
    drawer.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function onCommand(cmd: string, row: Env) {
  if (cmd === 'delete') void remove(row)
  if (cmd === 'default') void setDefault(row)
}

async function setDefault(row: Env) {
  await envApi.update(row.id, {
    code: row.code,
    name: row.name,
    color: row.color,
    parentEnvId: row.parentEnvId ?? null,
    isDefault: true
  })
  ElMessage.success('已设为默认环境')
  await load()
}

async function remove(row: Env) {
  try {
    await ElMessageBox.confirm(
      `删除环境「${row.name}」会级联清理模块绑定、变量覆盖与该环境下的文件。确认？`,
      '二次确认',
      { type: 'warning', confirmButtonText: '删除' }
    )
    await envApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 取消
  }
}

async function onDrop(target: number) {
  const from = dragIndex.value
  dragIndex.value = null
  if (from === null || from === target) return
  const arr = [...environments.value]
  const [moved] = arr.splice(from, 1)
  arr.splice(target, 0, moved)
  environments.value = arr
  await envApi.sort(arr.map((e) => e.id))
}

onMounted(load)
</script>

<style scoped>
.ph-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  padding-top: 2px;
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
}

.chain-card {
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

.card-body {
  padding: 16px;
}

.chain {
  display: flex;
  align-items: center;
  gap: 0;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.chain:last-child {
  margin-bottom: 0;
}

.chain-node {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-size: 12.5px;
  box-shadow: var(--sh-1);
}

.node-code {
  font-size: 12px;
}

.chain-node .dot {
  width: 8px;
  height: 8px;
  border-radius: 99px;
}

.chain-arrow {
  color: #c9cdd6;
  margin: 0 6px;
  display: flex;
}

.table-scroll {
  overflow-x: auto;
}

table {
  width: 100%;
  min-width: 660px;
  border-collapse: separate;
  border-spacing: 0;
}

/* 窄屏：给「环境」列留出名字宽度，其余列按原宽度滚动 */
@media (max-width: 600px) {
  table {
    min-width: 780px;
  }
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
  height: 44px;
  border-bottom: 1px solid #f0f1f5;
  font-size: 13px;
  vertical-align: middle;
}

tbody tr:last-child td {
  border-bottom: 0;
}

tbody tr:hover td {
  background: #fbfcfe;
}

tr.dragging td {
  background: var(--primary-50);
}

.grip {
  color: #c3c9d4;
  display: inline-flex;
  cursor: grab;
}

tr:hover .grip {
  color: var(--text-3);
}

.num {
  font-variant-numeric: tabular-nums;
}

.mono {
  font-family: var(--mono);
  font-size: 12.5px;
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
}

.tag-danger {
  background: var(--danger-50);
  color: var(--danger);
  border: 1px solid #f8d4d4;
}

.empty-row {
  height: 88px;
  text-align: center;
  color: var(--text-3);
  font-size: 12.5px;
}
</style>
