<template>
  <el-popover
    v-model:visible="open"
    trigger="click"
    placement="bottom-start"
    :width="264"
    popper-class="fsel-pop"
    :show-arrow="false"
    :offset="6"
  >
    <template #reference>
      <button type="button" class="field">
        <span class="muted">{{ label }}</span>
        <b>{{ currentLabel }}</b>
        <ProtoIcon name="chevron" :size="13" style="color: #8b93a1" />
      </button>
    </template>

    <div class="fsel">
      <input
        v-if="options.length > 7"
        v-model="q"
        class="fsel-q"
        :placeholder="`搜索${label}`"
        spellcheck="false"
      />
      <div class="fsel-list">
        <button
          type="button"
          class="fsel-item"
          :class="{ on: !modelValue }"
          @click="pick('')"
        >
          <span class="fi-t">{{ allLabel }}</span>
          <ProtoIcon v-if="!modelValue" name="check" :size="12" />
        </button>
        <button
          v-for="o in filtered"
          :key="o.value"
          type="button"
          class="fsel-item"
          :class="{ on: modelValue === o.value }"
          @click="pick(o.value)"
        >
          <span class="fi-t">{{ o.label }}</span>
          <ProtoIcon v-if="modelValue === o.value" name="check" :size="12" />
        </button>
        <div v-if="!filtered.length" class="fsel-empty">没有匹配项</div>
      </div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ProtoIcon from '@/components/ProtoIcon.vue'

interface Option {
  value: string
  label: string
}

const props = withDefaults(
  defineProps<{
    label: string
    modelValue: string
    options: Option[]
    allLabel?: string
  }>(),
  { allLabel: '全部' }
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()

const open = ref(false)
const q = ref('')

const currentLabel = computed(
  () => props.options.find((o) => o.value === props.modelValue)?.label || props.allLabel
)

const filtered = computed(() => {
  const kw = q.value.trim().toLowerCase()
  if (!kw) return props.options
  return props.options.filter(
    (o) =>
      o.label.toLowerCase().includes(kw) || o.value.toLowerCase().includes(kw)
  )
})

watch(open, (v) => {
  if (v) q.value = ''
})

function pick(v: string) {
  emit('update:modelValue', v)
  open.value = false
}
</script>

<style scoped>
.field {
  gap: 6px;
}

.field b {
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

<style>
/* popper 挂在 body 上，作用域样式无效 */
.fsel-pop.el-popover.el-popper {
  padding: 6px;
}

.fsel-q {
  display: block;
  width: 100%;
  height: 28px;
  padding: 0 8px;
  margin-bottom: 6px;
  border: 1px solid var(--border-2);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  font-family: inherit;
  font-size: 12px;
  color: var(--text);
  outline: none;
}

.fsel-q:focus {
  border-color: var(--primary);
  background: var(--surface);
}

.fsel-list {
  max-height: 272px;
  overflow: auto;
  overscroll-behavior: contain;
}

.fsel-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--text-2);
  text-align: left;
  cursor: pointer;
}

.fsel-item:hover {
  background: var(--surface-3);
  color: var(--text);
}

.fsel-item.on {
  background: var(--primary-50);
  color: var(--primary-700);
  font-weight: 500;
}

.fsel-item svg {
  color: var(--primary);
  flex: 0 0 auto;
}

.fi-t {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fsel-empty {
  padding: 14px 8px;
  text-align: center;
  font-size: 12px;
  color: var(--text-3);
}
</style>
