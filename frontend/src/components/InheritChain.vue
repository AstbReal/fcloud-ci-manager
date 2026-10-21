<template>
  <Teleport to="body">
    <div
      v-if="open"
      ref="popRef"
      class="chain-pop"
      :style="pos"
      role="dialog"
      aria-label="继承链"
      @click.stop
    >
      <div class="chain-h">
        <span class="mono chain-key">{{ data?.varKey || varKey }}</span>
        <span class="chain-env">{{ envName }}</span>
        <span class="chain-title">继承链</span>
        <button class="chain-x" title="关闭" @click="emit('close')">
          <el-icon :size="13"><Close /></el-icon>
        </button>
      </div>

      <div class="chain-body">
        <template v-if="data">
          <div
            v-for="(l, i) in data.levels"
            :key="i"
            class="chain-row"
            :class="{ hit: i === hitIndex, self: i === 0, und: !l.defined }"
          >
            <span class="nm">{{ l.environmentName }}</span>
            <span v-if="i === 0" class="lv lv-plain">当前格</span>
            <span v-if="i === hitIndex" class="lv">命中</span>
            <span class="v">
              {{ l.defined ? (l.masked ? '••••••' : l.value || '—') : '未定义' }}
            </span>
          </div>
        </template>
        <div v-else class="chain-empty">
          {{ loading ? '加载继承链…' : '没有可用的继承数据' }}
        </div>
      </div>

      <div class="chain-f">
        <span class="tip">更具体环境优先</span>
        <button class="chain-btn" @click="emit('override')">在 {{ envName }} 覆盖</button>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { ChainData } from '@/types/module'

const props = defineProps<{
  open: boolean
  anchor: HTMLElement | null
  data: ChainData | null
  loading: boolean
  varKey: string
  envName: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'override'): void
}>()

const popRef = ref<HTMLElement | null>(null)
const pos = reactive({ left: '0px', top: '0px' })

const hitIndex = computed(() => {
  if (!props.data) return -1
  return props.data.levels.findIndex((l) => l.defined)
})

function place() {
  const anchor = props.anchor
  if (!anchor) return
  const r = anchor.getBoundingClientRect()
  const w = 332
  const h = popRef.value?.offsetHeight || 300
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
  if (!props.open) return
  const t = e.target as Node
  if (popRef.value?.contains(t)) return
  if (props.anchor?.contains(t)) return
  emit('close')
}

function onEsc(e: KeyboardEvent) {
  if (props.open && e.key === 'Escape') emit('close')
}

function onViewportChange() {
  if (props.open) place()
}

watch(
  () => [props.open, props.data, props.anchor],
  async () => {
    if (!props.open) return
    await nextTick()
    place()
  },
  { deep: false }
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
.chain-pop {
  position: fixed;
  z-index: 3000;
  width: 332px;
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--r);
  box-shadow: var(--sh-2);
  overflow: hidden;
}

.chain-h {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
}

.chain-key {
  font-size: 12px;
  font-weight: 500;
  color: var(--text);
}

.chain-env {
  font: 500 10.5px var(--mono);
  color: var(--primary-700);
  background: var(--primary-50);
  border: 1px solid var(--primary-100);
  border-radius: 4px;
  padding: 0 4px;
  line-height: 15px;
}

.chain-title {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--text-3);
}

.chain-x {
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 4px;
  background: none;
  color: var(--text-3);
  cursor: pointer;
  display: grid;
  place-items: center;
}

.chain-x:hover {
  background: var(--surface-3);
  color: var(--text);
}

.chain-body {
  padding: 6px;
}

.chain-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  border-radius: var(--r-sm);
  position: relative;
}

.chain-row + .chain-row {
  border-top: 1px solid #f4f5f7;
}

.chain-row.hit {
  background: var(--primary-50);
}

.chain-row.hit::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--primary);
}

.chain-row .nm {
  flex: 0 0 auto;
  min-width: 50px;
  font: 500 12px var(--mono);
  color: var(--text-2);
}

.chain-row.self .nm {
  color: var(--text);
  font-weight: 600;
}

.chain-row.und .nm {
  color: var(--text-3);
}

.lv {
  flex: 0 0 auto;
  padding: 0 4px;
  border: 1px solid var(--primary-100);
  border-radius: 4px;
  background: #fff;
  color: var(--primary-700);
  font-size: 10px;
  font-weight: 600;
  line-height: 14px;
}

.lv-plain {
  border-color: var(--border);
  background: var(--surface-2);
  color: var(--text-3);
}

.chain-row .v {
  margin-left: auto;
  min-width: 0;
  flex: 0 1 auto;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font: 500 12px var(--mono);
  color: var(--text);
}

.chain-row.hit .v {
  color: var(--primary-700);
  font-weight: 600;
}

.chain-row.und .v {
  color: var(--text-3);
  font-family: var(--sans);
  font-weight: 400;
}

.chain-empty {
  padding: 20px 10px;
  text-align: center;
  font-size: 12px;
  color: var(--text-3);
}

.chain-f {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--border);
  background: var(--surface-2);
}

.chain-f .tip {
  font-size: 11px;
  color: var(--text-3);
}

.chain-btn {
  margin-left: auto;
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--primary);
  border-radius: var(--r-sm);
  background: var(--primary);
  color: #fff;
  font-size: 11.5px;
  font-weight: 500;
  cursor: pointer;
}

.chain-btn:hover {
  background: var(--primary-600);
  border-color: var(--primary-600);
}
</style>
