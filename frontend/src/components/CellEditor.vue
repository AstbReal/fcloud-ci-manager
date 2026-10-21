<template>
  <div
    ref="root"
    class="cell"
    :class="stateClass"
    :tabindex="editing ? -1 : 0"
    :title="titleText"
    @click="onCellClick"
    @keydown.enter.prevent="onEnter"
  >
    <template v-if="editing">
      <input
        ref="inputRef"
        v-model="draft"
        class="cell-input"
        :type="isEncrypted ? 'password' : 'text'"
        :placeholder="isEncrypted && cell?.found ? '输入新值以轮换' : '输入值'"
        spellcheck="false"
        @keydown.enter.prevent="commit"
        @keydown.esc.prevent="cancel"
        @blur="onBlur"
      />
    </template>

    <template v-else-if="!cell || (!cell.found && !cell.inherited)">
      <span class="txt ph">
        {{ row.isRequired && scope === 'ENV' ? '必填未配置' : '未配置' }}
      </span>
    </template>

    <template v-else-if="cell.masked">
      <span class="txt secret">••••••</span>
      <button
        v-if="row.canReveal"
        class="eye"
        title="查看明文"
        @click.stop="reveal"
      >
        <el-icon :size="13"><View /></el-icon>
      </button>
    </template>

    <template v-else-if="cell.inherited">
      <span class="arrow">↳</span>
      <span class="txt inherit">继承</span>
      <button class="chain-btn" title="查看继承链" @click.stop="emitChain">
        <el-icon :size="11"><QuestionFilled /></el-icon>
      </button>
    </template>

    <template v-else>
      <span class="txt">{{ cell.value }}</span>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled, View } from '@element-plus/icons-vue'
import { api } from '@/api/http'
import type { Env, MatrixCell, MatrixRow } from '@/types/module'
import type { Id } from '@/types/id'

const props = defineProps<{
  row: MatrixRow
  cell?: MatrixCell
  scope: 'GLOBAL' | 'ENV'
  environmentId?: Id
  environments?: Env[]
}>()

const emit = defineEmits<{
  (e: 'saved'): void
  (e: 'chain', anchor: HTMLElement): void
}>()

const root = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)
const editing = ref(false)
const draft = ref('')
const cancelling = ref(false)
const committing = ref(false)

const isEncrypted = computed(() => props.row.valueType === 'ENCRYPTED')

const isValue = computed(
  () => !!props.cell?.found && !props.cell?.inherited && !props.cell?.masked
)

const isEmpty = computed(
  () => !props.cell || (!props.cell.found && !props.cell.inherited)
)

const stateClass = computed(() => ({
  'is-val': isValue.value,
  'is-secret': !!props.cell?.masked,
  'is-inherit': !!props.cell?.inherited,
  'is-empty': isEmpty.value,
  // 只标环境列：全局值本就是可选的，标红会误导
  'is-missing': isEmpty.value && props.row.isRequired && props.scope === 'ENV',
  ov: !!props.cell?.override
}))

const titleText = computed(() => {
  if (props.cell?.masked) return '加密值 · 点击眼睛查看明文'
  if (props.cell?.inherited) return '该值继承自父环境或全局值，点击查看继承链'
  if (!props.cell?.found) return '未配置 · 点击填写'
  return props.scope === 'GLOBAL'
    ? '全局值 · 点击编辑'
    : `${props.environments?.find((e) => e.id === props.environmentId)?.name || '环境'}覆盖 · 点击编辑`
})

function onCellClick() {
  if (editing.value) return
  if (props.cell?.inherited) {
    emitChain()
    return
  }
  startEdit()
}

function onEnter() {
  if (editing.value) return
  if (props.cell?.inherited) {
    emitChain()
    return
  }
  startEdit()
}

function emitChain() {
  if (root.value) emit('chain', root.value)
}

function startEdit() {
  cancelling.value = false
  draft.value =
    props.cell?.masked || props.cell?.inherited ? '' : (props.cell?.value ?? '')
  editing.value = true
  nextTick(() => inputRef.value?.focus())
}

function cancel() {
  cancelling.value = true
  editing.value = false
  draft.value = ''
}

function onBlur() {
  if (!editing.value) return
  if (cancelling.value) {
    cancelling.value = false
    return
  }
  void commit()
}

async function commit() {
  if (!editing.value || committing.value) return
  const value = draft.value
  // 原值：加密值/继承格没有可编辑的原值，一律当作空
  const original =
    props.cell?.masked || props.cell?.inherited ? '' : (props.cell?.value ?? '')
  // 点进编辑框又直接点走 = 没有修改，不要凭空写出一条覆盖
  if (value === original) {
    cancel()
    return
  }
  if (value === '') {
    // 加密值留空 = 取消，避免误清空已有密文
    if (isEncrypted.value && props.cell?.found) {
      cancel()
      return
    }
    if (props.cell?.found && !props.cell.inherited) {
      committing.value = true
      try {
        await clearValue()
      } finally {
        committing.value = false
      }
    } else {
      cancel()
    }
    return
  }

  committing.value = true
  try {
    await api.put(
      `/api/console/modules/${moduleId()}/variables/${props.row.id}/value`,
      {
        scope: props.scope,
        environmentId: props.scope === 'ENV' ? props.environmentId : null,
        value
      }
    )
    editing.value = false
    draft.value = ''
    ElMessage.success('已保存')
    emit('saved')
  } finally {
    committing.value = false
  }
}

async function clearValue() {
  if (props.scope === 'GLOBAL') {
    await api.put(
      `/api/console/modules/${moduleId()}/variables/${props.row.id}/value`,
      { scope: 'GLOBAL', value: '' }
    )
    ElMessage.success('已清空全局值')
  } else {
    await api.del(
      `/api/console/modules/${moduleId()}/variables/${props.row.id}/override` +
        `?environmentId=${props.environmentId}`
    )
    ElMessage.success('已清除覆盖，回退继承')
  }
  editing.value = false
  draft.value = ''
  emit('saved')
}

async function reveal() {
  const envId =
    props.scope === 'ENV'
      ? props.environmentId
      : (props.environments?.[0]?.id ?? null)
  if (!envId) {
    ElMessage.warning('请先给模块绑定环境')
    return
  }
  const res = await api.post<{ plainValue: string }>(
    `/api/console/modules/${moduleId()}/variables/${props.row.id}/reveal`,
    { environmentId: envId }
  )
  await ElMessageBox.alert(
    `<div class="fc-mono" style="word-break:break-all">${escapeHtml(res.plainValue)}</div>`,
    `明文：${props.row.varKey}`,
    {
      dangerouslyUseHTMLString: true,
      confirmButtonText: '关闭',
      type: 'warning'
    }
  )
}

function escapeHtml(s: string) {
  return s.replace(/[&<>"']/g, (c) =>
    ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;'
    })[c] as string
  )
}

function moduleId(): string {
  const m = location.pathname.match(/\/modules\/(\d+)/)
  return m ? m[1] : ''
}

defineExpose({ startEdit })
</script>

<style scoped>
.cell {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  height: 32px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  font: 500 12.5px var(--mono);
  color: var(--text);
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  cursor: text;
  transition: border-color 0.12s, background 0.12s;
}

.cell:focus-visible {
  outline: 2px solid rgba(37, 99, 235, 0.45);
  outline-offset: 1px;
}

.cell:hover {
  border-color: var(--border-2);
  background: #fff;
}

.txt {
  overflow: hidden;
  text-overflow: ellipsis;
}

.is-val {
  background: #fff;
  border-color: #eceef3;
}

.is-val.ov {
  border-left: 2px solid var(--primary);
  padding-left: 9px;
  background: #fcfdff;
}

.is-val.ov:hover {
  border-left-color: var(--primary-600);
}

.is-inherit {
  color: #7d8595;
  font-family: var(--mono);
}

.is-inherit .arrow {
  color: #b3bac6;
  font-family: var(--sans);
}

.is-inherit:hover {
  background: #fff;
}

.is-empty {
  color: #7d8595;
  border: 1px dashed #e1e4ea;
  background: #fcfcfd;
}

.is-empty .ph {
  font-size: 12px;
  font-family: var(--sans);
}

/* 必填未配置：红色虚线 + 浅红底，和"可选的未配置"区分开 */
.is-empty.is-missing {
  color: var(--danger);
  border-color: #f3b4b4;
  background: #fffbfb;
}

.is-empty.is-missing .ph {
  color: #d24a4a;
  font-weight: 500;
}

.is-secret {
  color: var(--text-2);
  background: #fcfcfd;
  border-color: #eef0f4;
}

.is-secret.ov {
  border-left: 2px solid var(--primary);
  padding-left: 9px;
  background: #fcfdff;
}

.secret {
  letter-spacing: 0.16em;
}

.eye {
  margin-left: auto;
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  border: 0;
  border-radius: 4px;
  background: none;
  color: var(--text-3);
  cursor: pointer;
  display: grid;
  place-items: center;
}

.eye:hover {
  color: var(--primary);
  background: var(--primary-50);
}

.chain-btn {
  width: 17px;
  height: 17px;
  flex: 0 0 17px;
  border: 0;
  border-radius: 99px;
  background: none;
  color: #aab1bd;
  cursor: pointer;
  display: grid;
  place-items: center;
}

.chain-btn:hover {
  color: var(--primary);
  background: var(--primary-50);
}

.cell-input {
  width: 100%;
  height: 30px;
  padding: 0 7px;
  border: 1px solid var(--primary);
  border-radius: var(--r-sm);
  background: #fff;
  color: var(--text);
  font: 500 12.5px var(--mono);
  outline: 0;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.cell-input::placeholder {
  color: #98a1b0;
  font-family: var(--sans);
  font-weight: 400;
}
</style>
