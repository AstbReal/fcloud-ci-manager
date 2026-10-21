<template>
  <div class="mx-tool">
    <div class="mx-search" :class="{ focused }">
      <el-icon :size="14" color="#6b7584"><Search /></el-icon>
      <input
        ref="inputRef"
        v-model="kw"
        placeholder="搜变量、值、备注"
        spellcheck="false"
        @input="onInput"
        @focus="focused = true"
        @blur="focused = false"
        @keydown.esc="clearKw"
      />
      <kbd v-if="!kw">/</kbd>
      <button v-else class="mx-clear" title="清空搜索" @click="clearKw">
        <el-icon :size="12"><Close /></el-icon>
      </button>
    </div>

    <div class="pills">
      <button class="pf" :class="{ on: !prefix }" @click="setPrefix('')">
        全部 <i>{{ rows.length }}</i>
      </button>
      <button
        v-for="p in prefixPills"
        :key="p.key"
        class="pf"
        :class="{ on: prefix === p.key }"
        @click="setPrefix(p.key)"
      >
        {{ p.key }}<i>{{ p.count }}</i>
      </button>
      <button
        class="pf"
        :class="{ on: prefix === '__empty' }"
        @click="setPrefix('__empty')"
      >
        未配置 <i>{{ emptyCount }}</i>
      </button>
      <button
        class="pf"
        :class="{ on: prefix === '__override' }"
        @click="setPrefix('__override')"
      >
        有环境覆盖 <i>{{ overrideCount }}</i>
      </button>
    </div>

    <div class="mx-count">
      显示 <b>{{ visibleCount }}</b> / {{ rows.length }}
      <button v-if="filtering" class="mx-reset" @click="clearAll">清除</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { MatrixRow } from '@/types/module'

const props = defineProps<{
  rows: MatrixRow[]
  modelValue: string
  prefix: string
  visibleCount: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'update:prefix', v: string): void
}>()

const inputRef = ref<HTMLInputElement | null>(null)
const focused = ref(false)
const kw = ref(props.modelValue)
let timer: number | undefined

const filtering = computed(() => !!kw.value || !!props.prefix)

const prefixPills = computed(() => {
  const map = new Map<string, number>()
  for (const r of props.rows) {
    const m = r.varKey.match(/^([A-Za-z][A-Za-z0-9]*)_/)
    if (!m) continue
    const key = `${m[1].toUpperCase()}_`
    map.set(key, (map.get(key) || 0) + 1)
  }
  return Array.from(map.entries())
    .map(([key, count]) => ({ key, count }))
    .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key))
})

function envCells(r: MatrixRow) {
  return Object.entries(r.cells)
    .filter(([k]) => k !== '0')
    .map(([, c]) => c)
}

const emptyCount = computed(
  () => props.rows.filter((r) => envCells(r).some((c) => !c || !c.found)).length
)

const overrideCount = computed(
  () =>
    props.rows.filter(
      (r) => Object.values(r.cells).some((c) => c.override)
    ).length
)

function onInput() {
  window.clearTimeout(timer)
  timer = window.setTimeout(() => emit('update:modelValue', kw.value), 150)
}

function clearKw() {
  kw.value = ''
  window.clearTimeout(timer)
  emit('update:modelValue', '')
}

function setPrefix(p: string) {
  emit('update:prefix', props.prefix === p ? '' : p)
}

function clearAll() {
  kw.value = ''
  window.clearTimeout(timer)
  emit('update:modelValue', '')
  emit('update:prefix', '')
}

watch(
  () => props.modelValue,
  (v) => {
    if (v !== kw.value) kw.value = v
  }
)

defineExpose({
  focus() {
    inputRef.value?.focus()
    inputRef.value?.select()
  }
})
</script>

<style scoped>
.mx-tool {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.mx-search {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 272px;
  flex: 0 0 272px;
  height: 32px;
  padding: 0 9px;
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--r-sm);
}

.mx-search.focused {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.mx-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: none;
  font: 400 12.5px var(--sans);
  color: var(--text);
}

.mx-search input::placeholder {
  color: #98a1b0;
}

.mx-search kbd {
  font: 600 10.5px/15px var(--mono);
  color: var(--text-3);
  border: 1px solid var(--border-2);
  border-bottom-width: 2px;
  border-radius: 4px;
  padding: 0 4px;
}

.mx-clear {
  border: 0;
  background: none;
  padding: 0;
  color: var(--text-3);
  cursor: pointer;
  display: grid;
  place-items: center;
}

.mx-clear:hover {
  color: var(--text);
}

.pills {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.pf {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--border);
  border-radius: 99px;
  background: var(--surface);
  color: var(--text-2);
  font: 500 12px var(--mono);
  white-space: nowrap;
  cursor: pointer;
  transition: 0.14s;
}

.pf i {
  font-style: normal;
  font-size: 10.5px;
  color: var(--text-3);
  font-family: var(--sans);
}

.pf:hover {
  border-color: var(--border-2);
  color: var(--text);
}

.pf.on {
  background: var(--primary-50);
  border-color: var(--primary-100);
  color: var(--primary-700);
  font-weight: 600;
}

.pf.on i {
  color: var(--primary-600);
}

.mx-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.mx-count b {
  color: var(--text);
  font-weight: 600;
}

.mx-reset {
  margin-left: 6px;
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--primary);
  cursor: pointer;
}
</style>
