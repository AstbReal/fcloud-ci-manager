<template>
  <Teleport to="body">
    <div
      v-if="modelValue && app"
      ref="popRef"
      class="picker"
      :style="pos"
      role="dialog"
      aria-label="订阅共享模块"
      @click.stop
    >
      <div class="picker-h">
        <span>订阅共享模块</span>
        <span class="mono">{{ app.code }}</span>
        <button class="picker-x" title="关闭" @click="close">
          <el-icon :size="14"><Close /></el-icon>
        </button>
      </div>

      <div class="picker-s">
        <el-icon :size="14" color="#6b7584"><Search /></el-icon>
        <input
          ref="kwRef"
          v-model="kw"
          placeholder="搜索模块名 / code"
          spellcheck="false"
        />
        <button v-if="kw" class="picker-clear" @click="kw = ''">清除</button>
      </div>

      <div class="picker-tip">
        订阅是<b>引用</b>同一份配置：共享模块改动会实时同步到本应用；
        需要本应用独有的值时，进模块对变量做应用级覆盖。
      </div>

      <div class="picker-b">
        <div
          v-for="m in filtered"
          :key="m.id"
          class="pk-row"
          :class="{ on: isSub(m.id) }"
          @click="toggle(m)"
        >
          <el-checkbox
            :model-value="isSub(m.id)"
            @click.stop
            @change="toggle(m)"
          />
          <div class="pk-main">
            <div class="pk-t">
              <span>{{ m.name }}</span>
              <span class="pk-code">{{ m.code }}</span>
              <span v-if="isSub(m.id)" class="tag tag-shared">已订阅</span>
            </div>
            <div class="pk-s">{{ m.description || '暂无描述' }}</div>
          </div>
          <span class="pk-count">{{ m.subscriptionCount }} 个应用</span>
        </div>

        <div v-if="!modules.length" class="pk-empty">
          还没有共享模块。先在左侧「共享模块库」新建一个。
        </div>
        <div v-else-if="!filtered.length" class="pk-empty">
          没有匹配「{{ kw }}」的共享模块。
        </div>
      </div>

      <div class="picker-f">
        勾选即生效，可随时取消
        <span class="pf-count">{{ subscribedIds.size }} / {{ modules.length }}</span>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { BoardApp, SharedCard } from '@/types/board'
import type { Id } from '@/types/id'

const props = defineProps<{
  modelValue: boolean
  app: BoardApp | null
  modules: SharedCard[]
  anchor: HTMLElement | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'toggle', moduleId: Id): void
}>()

const popRef = ref<HTMLElement | null>(null)
const kwRef = ref<HTMLInputElement | null>(null)
const kw = ref('')

const pos = reactive({ left: '0px', top: '0px' })

const subscribedIds = computed(
  () => new Set((props.app?.subscribedModules || []).map((m) => m.id))
)

const filtered = computed(() => {
  const q = kw.value.trim().toLowerCase()
  if (!q) return props.modules
  return props.modules.filter(
    (m) =>
      m.name.toLowerCase().includes(q) ||
      m.code.toLowerCase().includes(q) ||
      (m.description || '').toLowerCase().includes(q)
  )
})

function isSub(id: Id) {
  return subscribedIds.value.has(id)
}

function toggle(m: SharedCard) {
  emit('toggle', m.id)
}

function close() {
  emit('update:modelValue', false)
}

function place() {
  const anchor = props.anchor
  if (!anchor) return
  const r = anchor.getBoundingClientRect()
  const w = 330
  const h = Math.min(380, window.innerHeight - 24)
  let left = Math.min(r.left, window.innerWidth - w - 12)
  left = Math.max(12, left)
  let top = r.bottom + 6
  if (top + h > window.innerHeight - 8) {
    top = Math.max(8, r.top - h - 6)
  }
  pos.left = `${left}px`
  pos.top = `${top}px`
}

function onDocDown(e: MouseEvent) {
  if (!props.modelValue) return
  const t = e.target as Node
  if (popRef.value?.contains(t)) return
  if (props.anchor?.contains(t)) return
  close()
}

function onEsc(e: KeyboardEvent) {
  if (props.modelValue && e.key === 'Escape') {
    e.stopPropagation()
    close()
  }
}

function onViewportChange() {
  if (props.modelValue) place()
}

watch(
  () => props.modelValue,
  async (open) => {
    if (open) {
      kw.value = ''
      await nextTick()
      place()
      kwRef.value?.focus()
    }
  }
)

watch(
  () => props.anchor,
  () => {
    if (props.modelValue) place()
  }
)

onMounted(() => {
  document.addEventListener('mousedown', onDocDown)
  document.addEventListener('keydown', onEsc, true)
  window.addEventListener('resize', onViewportChange)
  window.addEventListener('scroll', onViewportChange, true)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocDown)
  document.removeEventListener('keydown', onEsc, true)
  window.removeEventListener('resize', onViewportChange)
  window.removeEventListener('scroll', onViewportChange, true)
})
</script>

<style scoped>
.picker {
  position: fixed;
  z-index: 3000;
  width: 330px;
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--r);
  box-shadow: var(--sh-2);
  overflow: hidden;
}

.picker-h {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
  font-size: 12.5px;
  font-weight: 600;
}

.picker-h .mono {
  font: 400 11.5px var(--mono);
  color: var(--text-3);
}

.picker-x {
  margin-left: auto;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 5px;
  background: none;
  color: var(--text-3);
  display: grid;
  place-items: center;
  cursor: pointer;
}

.picker-x:hover {
  background: var(--surface-3);
  color: var(--text);
}

.picker-s {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
}

.picker-s input {
  flex: 1;
  min-width: 0;
  height: 26px;
  border: 0;
  outline: 0;
  background: none;
  font: 400 12.5px var(--sans);
  color: var(--text);
}

.picker-s input::placeholder {
  color: #98a1b0;
}

.picker-clear {
  border: 0;
  background: none;
  padding: 0;
  font-size: 11.5px;
  color: var(--primary);
  cursor: pointer;
}

.picker-tip {
  margin: 0 14px 8px;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  background: var(--primary-50);
  border: 1px solid var(--primary-100);
  color: var(--text-2);
  font-size: 11.5px;
  line-height: 1.55;
}

.picker-tip b {
  color: var(--text);
  font-weight: 600;
}

.picker-b {
  max-height: 252px;
  overflow: auto;
  padding: 4px;
}

.pk-row {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 8px;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.pk-row:hover {
  background: var(--surface-3);
}

.pk-row.on {
  background: #fbfcff;
}

.pk-main {
  flex: 1;
  min-width: 0;
}

.pk-t {
  display: flex;
  align-items: center;
  gap: 6px;
  font: 500 12.5px var(--mono);
  color: var(--text);
}

.pk-code {
  font: 400 11px var(--mono);
  color: var(--text-3);
}

.pk-s {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
}

.pk-count {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
}

.pk-empty {
  padding: 22px 10px;
  text-align: center;
  font-size: 12px;
  color: var(--text-3);
}

.picker-f {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-top: 1px solid var(--border);
  background: var(--surface-2);
  font-size: 11px;
  color: var(--text-3);
}

.pf-count {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
  color: var(--text-2);
}
</style>
