<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="480px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="mod-form">
      <div v-if="!isEdit && !lockKind" class="fld">
        <span class="fld-lb">归属</span>
        <div class="kind-switch">
          <button
            type="button"
            class="kind-opt"
            :class="{ on: form.kind === 'private' }"
            @click="form.kind = 'private'"
          >
            <span class="k-t">私有模块</span>
            <span class="k-s">属于某个应用，仅本应用可见</span>
          </button>
          <button
            type="button"
            class="kind-opt"
            :class="{ on: form.kind === 'shared' }"
            @click="form.kind = 'shared'"
          >
            <span class="k-t">共享模块</span>
            <span class="k-s">多应用订阅同一份，改动实时同步</span>
          </button>
        </div>
      </div>

      <div v-if="!isEdit" class="fld">
        <span class="fld-lb">
          所属应用<span v-if="form.kind === 'private'" class="req">*</span>
        </span>
        <el-select
          v-if="form.kind === 'private'"
          v-model="form.appId"
          filterable
          placeholder="选择应用"
          style="width: 100%"
        >
          <el-option
            v-for="a in apps"
            :key="a.id"
            :label="`${a.name || a.code} (${a.code})`"
            :value="a.id"
          />
        </el-select>
        <div v-else class="field-static">共享模块不属于任何应用</div>
      </div>

      <div class="fld-grid">
        <label class="fld">
          <span class="fld-lb">
            Code<span class="req">*</span>
            <span v-if="isEdit" class="fld-opt">创建后不可修改</span>
          </span>
          <el-input
            v-model="form.code"
            :disabled="isEdit"
            class="k-input"
            :placeholder="form.kind === 'shared' ? 'common-notify' : 'db-config'"
            @blur="form.code = form.code.trim()"
          />
        </label>
        <label class="fld">
          <span class="fld-lb">名称<span class="req">*</span></span>
          <el-input v-model="form.name" placeholder="数据库配置" />
        </label>
      </div>

      <label class="fld">
        <span class="fld-lb">
          描述
          <span class="fld-opt">选填，说明它的职责和适用场景</span>
        </span>
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          placeholder="这个模块负责什么，什么时候该用它"
        />
      </label>

      <div class="fld">
        <span class="fld-lb">分组<span class="fld-opt">看板按它把模块归在一起</span></span>
        <el-select
          v-if="form.kind === 'private'"
          v-model="form.groupName"
          filterable
          allow-create
          clearable
          default-first-option
          placeholder="选择已有分组，或直接输入新名称"
          style="width: 100%"
        >
          <el-option v-for="g in groupPresets" :key="g" :label="g" :value="g" />
        </el-select>
        <div v-else class="field-static">共享模块不参与应用内分组</div>
      </div>

      <div class="fld">
        <span class="fld-lb">
          绑定环境
          <span class="fld-opt">
            {{ form.environmentIds.length }} / {{ environments.length }}
          </span>
          <button
            v-if="environments.length > 1"
            type="button"
            class="mini-link"
            @click="toggleAllEnvs"
          >
            {{ allEnvSelected ? '全不选' : '全选' }}
          </button>
        </span>

        <div v-if="environments.length" class="env-chips">
          <button
            v-for="e in environments"
            :key="e.id"
            type="button"
            class="env-chip"
            :class="{ on: isEnvOn(e.id) }"
            @click="toggleEnv(e.id)"
          >
            <i class="dot" :style="{ background: e.color || '#2563eb' }" />
            <span class="ec-name">{{ e.name }}</span>
            <span class="ec-code">{{ e.code }}</span>
            <ProtoIcon v-if="isEnvOn(e.id)" name="check" :size="12" class="ec-check" />
          </button>
        </div>
        <div v-else class="field-static">还没有环境，请先到「环境管理」创建</div>

        <div class="callout" :class="{ warn: !form.environmentIds.length }">
          <ProtoIcon
            :name="form.environmentIds.length ? 'layers' : 'alert'"
            :size="13"
            class="c-ic"
          />
          <span v-if="form.environmentIds.length">
            矩阵为每个绑定环境生成一列；流水线只能拉取已绑定环境的配置。
          </span>
          <span v-else>
            一个都没绑：矩阵只有「全局值」一列，不能按环境覆盖，也不能复制 dotenv / 上传文件。
          </span>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="drawer-foot">
        <span class="foot-note">
          {{
            isEdit
              ? '保存后矩阵列会按绑定环境重算'
              : '创建后可在矩阵里按环境逐个填值'
          }}
        </span>
        <div class="foot-actions">
          <button class="btn" @click="emit('update:modelValue', false)">取消</button>
          <button class="btn btn-primary" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ProtoIcon from '@/components/ProtoIcon.vue'
import { api } from '@/api/http'
import type { Id } from '@/types/id'

export interface EnvOption {
  id: Id
  code: string
  name: string
  color?: string
}

export interface AppOption {
  id: Id
  code: string
  name?: string
}

export interface EditableModule {
  id: Id
  code: string
  name: string
  description?: string
  groupName?: string
}

const props = defineProps<{
  modelValue: boolean
  kind: 'private' | 'shared'
  appId?: Id
  module?: EditableModule | null
  environments: EnvOption[]
  apps?: AppOption[]
  groups?: string[]
  lockKind?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const saving = ref(false)
const form = reactive<{
  kind: 'private' | 'shared'
  appId?: Id
  code: string
  name: string
  description: string
  groupName: string
  environmentIds: Id[]
}>({
  kind: 'private',
  appId: undefined,
  code: '',
  name: '',
  description: '',
  groupName: '',
  environmentIds: []
})

/** 只列库里真实存在的分组（来自 groups prop），不再内置预设，避免出现删不掉的分组。 */
const groupPresets = computed(() =>
  Array.from(new Set((props.groups || []).filter((g) => !!g)))
)

const isEdit = computed(() => !!props.module)
const title = computed(() => {
  if (isEdit.value) return `编辑${props.kind === 'shared' ? '共享' : ''}模块`
  return form.kind === 'shared' ? '新建共享模块' : '新建模块'
})

const allEnvSelected = computed(
  () =>
    props.environments.length > 0 &&
    props.environments.every((e) => isEnvOn(e.id))
)

function isEnvOn(id: Id) {
  return form.environmentIds.some((x) => String(x) === String(id))
}

function toggleEnv(id: Id) {
  if (isEnvOn(id)) {
    form.environmentIds = form.environmentIds.filter(
      (x) => String(x) !== String(id)
    )
  } else {
    form.environmentIds = [...form.environmentIds, id]
  }
}

function toggleAllEnvs() {
  form.environmentIds = allEnvSelected.value
    ? []
    : props.environments.map((e) => e.id)
}

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    form.kind = props.kind
    form.appId = props.appId ?? props.apps?.[0]?.id
    form.code = props.module?.code || ''
    form.name = props.module?.name || ''
    form.description = props.module?.description || ''
    form.groupName = props.module?.groupName || ''
    form.environmentIds = props.environments.map((e) => e.id)
    if (!props.module) return
    form.environmentIds = []
    try {
      const d = await api.get<{
        description?: string
        groupName?: string
        environmentIds?: Id[]
      }>(`/api/console/admin/modules/${props.module.id}`)
      form.description = d.description || ''
      form.groupName = d.groupName || ''
      form.environmentIds = d.environmentIds || []
    } catch {
      // 详情拉取失败时保持空选择，保存仍可用
    }
  }
)

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('Code 与名称必填')
    return
  }
  if (!isEdit.value && form.kind === 'private' && !form.appId) {
    ElMessage.warning('请选择所属应用')
    return
  }
  if (!form.environmentIds.length) {
    ElMessage.warning('至少绑定一个环境，否则矩阵无法按环境配置')
    return
  }
  saving.value = true
  try {
    const body = {
      code: form.code.trim(),
      name: form.name.trim(),
      description: form.description,
      groupName: form.kind === 'private' ? form.groupName || null : null,
      environmentIds: form.environmentIds
    }
    if (isEdit.value) {
      await api.put(`/api/console/admin/modules/${props.module!.id}`, body)
    } else if (form.kind === 'shared') {
      await api.post('/api/console/admin/shared-modules', body)
    } else {
      await api.post(`/api/console/admin/applications/${form.appId}/modules`, body)
    }
    ElMessage.success('已保存')
    emit('update:modelValue', false)
    emit('saved')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.mod-form {
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

.k-input :deep(.el-input__inner) {
  font-family: var(--mono);
  font-size: 12.5px;
}

.kind-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  width: 100%;
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

.field-static {
  display: flex;
  align-items: center;
  width: 100%;
  height: 32px;
  padding: 0 11px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--text-3);
  font-size: 12.5px;
}

.mini-link {
  margin-left: auto;
  padding: 0;
  border: 0;
  background: none;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--primary);
  cursor: pointer;
}

.mini-link:hover {
  text-decoration: underline;
}

.env-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.env-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 9px;
  border: 1px solid var(--border-2);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: inherit;
  font-size: 12.5px;
  color: var(--text-2);
  cursor: pointer;
  transition: 0.12s;
}

.env-chip:hover {
  border-color: #b9c0cc;
  background: var(--surface-2);
}

.env-chip.on {
  border-color: var(--primary);
  background: var(--primary-50);
  color: var(--primary-700);
}

.env-chip .dot {
  width: 7px;
  height: 7px;
  border-radius: 99px;
  flex: 0 0 7px;
}

.ec-name {
  font-weight: 500;
}

.ec-code {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--text-3);
}

.env-chip.on .ec-code {
  color: var(--primary);
}

.ec-check {
  color: var(--primary);
}

.callout {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 8px;
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

.callout.warn {
  background: var(--warn-50);
  border-color: #f5e0b0;
  color: var(--warn);
}

.callout.warn .c-ic {
  color: var(--warn);
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
</style>
