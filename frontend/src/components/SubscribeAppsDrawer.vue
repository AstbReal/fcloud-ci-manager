<template>
  <el-drawer
    :model-value="modelValue"
    size="460px"
    :with-header="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="drawer-head">
      <div>
        <div class="dh-title">
          <span class="mono">{{ module?.name }}</span>
          <span class="tag tag-shared">共享模块</span>
        </div>
        <div class="dh-sub">{{ module?.description || '订阅后，应用即可在流水线中引用该模块' }}</div>
      </div>
      <button class="dh-x" @click="emit('update:modelValue', false)">
        <el-icon :size="15"><Close /></el-icon>
      </button>
    </div>

    <div class="drawer-body">
      <div class="section-row">
        <div class="section-label">订阅应用（{{ subscribedCount }} / {{ apps.length }}）</div>
        <button
          v-if="missingCount > 0"
          class="bulk-btn"
          :disabled="busy"
          @click="emit('subscribeAll')"
        >
          {{ busy ? '订阅中…' : `全部订阅（+${missingCount}）` }}
        </button>
        <span v-else class="bulk-done">已全部订阅</span>
      </div>
      <div class="app-list">
        <div
          v-for="app in apps"
          :key="app.id"
          class="dw-row"
          :class="{ on: isSub(app) }"
          @click="emit('toggle', app.id)"
        >
          <el-checkbox :model-value="isSub(app)" @click.stop @change="emit('toggle', app.id)" />
          <div class="dw-ic">{{ (app.name || '?').charAt(0) }}</div>
          <div class="dw-main">
            <div class="dw-t">{{ app.name }}</div>
            <div class="dw-s mono">{{ app.code }}</div>
          </div>
          <span v-if="isSub(app)" class="tag tag-shared">已订阅</span>
          <span v-else class="dw-hint">点击订阅</span>
        </div>
      </div>

      <div class="drawer-note">
        <el-icon :size="14"><InfoFilled /></el-icon>
        <span>订阅只建立引用，不复制变量；取消订阅不会删除任何已配置的值。</span>
      </div>
    </div>

    <div class="drawer-foot">
      <el-button text type="primary" @click="emit('open')">打开变量矩阵 →</el-button>
      <div class="foot-right">
        <el-button size="small" @click="emit('edit')">编辑</el-button>
        <el-tooltip
          :disabled="subscribedCount === 0"
          content="仍有订阅方，先取消订阅才能删除"
          placement="top"
        >
          <span>
            <el-button
              size="small"
              type="danger"
              plain
              :disabled="subscribedCount > 0"
              @click="emit('remove')"
            >
              删除
            </el-button>
          </span>
        </el-tooltip>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { BoardApp, SharedCard } from '@/types/board'
import type { Id } from '@/types/id'

const props = defineProps<{
  modelValue: boolean
  module: SharedCard | null
  apps: BoardApp[]
  busy?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'toggle', appId: Id): void
  (e: 'subscribeAll'): void
  (e: 'edit'): void
  (e: 'remove'): void
  (e: 'open'): void
}>()

const subscribedCount = computed(
  () => props.apps.filter((a) => isSub(a)).length
)

const missingCount = computed(() => props.apps.length - subscribedCount.value)

function isSub(app: BoardApp) {
  return (app.subscribedModules || []).some((m) => m.id === props.module?.id)
}
</script>

<style scoped>
.drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
}

.dh-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
}

.dh-title .mono {
  font-size: 14px;
}

.dh-sub {
  margin-top: 5px;
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.5;
}

.dh-x {
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  color: var(--text-3);
  cursor: pointer;
  display: grid;
  place-items: center;
}

.dh-x:hover {
  background: var(--surface-3);
  color: var(--text);
}

.drawer-body {
  padding-top: 16px;
}

.section-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.section-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-3);
  letter-spacing: 0.05em;
}

.bulk-btn {
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--primary-100);
  border-radius: 5px;
  background: var(--primary-50);
  color: var(--primary-600);
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: 0.14s;
}

.bulk-btn:hover:not(:disabled) {
  background: var(--primary-100);
  border-color: var(--primary-100);
}

.bulk-btn:disabled {
  opacity: 0.6;
  cursor: progress;
}

.bulk-done {
  font-size: 11.5px;
  color: var(--text-3);
  white-space: nowrap;
}

.app-list {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.dw-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: 0.14s;
}

.dw-row:hover {
  border-color: var(--border-2);
  background: var(--surface-2);
}

.dw-row.on {
  border-color: var(--primary-100);
  background: #fbfcff;
}

.dw-ic {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border-radius: 7px;
  background: var(--surface-3);
  color: var(--text-2);
  display: grid;
  place-items: center;
  font: 650 11.5px var(--mono);
}

.dw-main {
  flex: 1;
  min-width: 0;
}

.dw-t {
  font-size: 13px;
  font-weight: 500;
}

.dw-s {
  font-size: 11.5px;
  color: var(--text-3);
}

.dw-hint {
  font-size: 11.5px;
  color: var(--text-3);
}

.drawer-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 18px;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--primary-50);
  border: 1px solid var(--primary-100);
  color: var(--text-2);
  font-size: 12px;
  line-height: 1.55;
}

.drawer-foot {
  position: sticky;
  bottom: -20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 18px -20px -20px;
  padding: 12px 20px;
  background: var(--surface);
  border-top: 1px solid var(--border);
}

.foot-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
