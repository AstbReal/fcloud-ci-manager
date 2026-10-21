<template>
  <span
    v-if="variant === 'dot' && level !== 'ok'"
    class="health-dot"
    :class="[level, size]"
    :title="title"
  />
  <span v-if="variant === 'badge' && level !== 'ok'" class="risk" :class="level">
    {{ badgeText }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ModuleHealth } from '@/types/board'

const props = withDefaults(
  defineProps<{
    health?: ModuleHealth | null
    variant?: 'dot' | 'badge'
    size?: 'sm' | 'md'
  }>(),
  { variant: 'dot', size: 'md', health: null }
)

const level = computed(() => {
  if (!props.health) return 'ok'
  if (props.health.missingRequired > 0) return 'red'
  if (props.health.templatePending) return 'amber'
  return 'ok'
})

const badgeText = computed(() => {
  if (!props.health) return ''
  if (props.health.missingRequired > 0) {
    return `${props.health.missingRequired} 处问题`
  }
  return '待同步'
})

const title = computed(() => {
  if (level.value === 'red') {
    return `缺必填变量：${props.health?.missingRequired} 处（变量 × 环境）`
  }
  if (level.value === 'amber') return '关联模板有未同步变更'
  return '正常'
})
</script>

<style scoped>
.health-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  flex: 0 0 6px;
  border-radius: 99px;
  background: #c3c9d4;
}

.health-dot.red {
  background: var(--danger);
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.1);
}

.health-dot.amber {
  background: #d97706;
  box-shadow: 0 0 0 3px rgba(217, 119, 6, 0.1);
}

.risk {
  display: inline-flex;
  align-items: center;
  height: 17px;
  padding: 0 5px;
  border-radius: 5px;
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}

.risk.red {
  color: var(--danger);
  background: var(--danger-50);
  border: 1px solid #f8d4d4;
}

.risk.amber {
  color: var(--warn);
  background: var(--warn-50);
  border: 1px solid #fce6b5;
}
</style>
