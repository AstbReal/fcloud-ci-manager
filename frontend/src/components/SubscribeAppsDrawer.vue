<template>
  <el-drawer
    :model-value="modelValue"
    size="460px"
    :show-close="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <template #header="{ close }">
      <div class="drawer-head">
        <div class="dh-main">
          <div class="dh-title">
            <span class="mono">{{ module?.name }}</span>
            <span class="tag tag-shared">共享模块</span>
          </div>
          <div class="dh-sub">
            {{ module?.description || '订阅后，应用即可在流水线中引用该模块' }}
          </div>
        </div>
        <button class="dh-x" aria-label="关闭" @click="close">
          <el-icon :size="15"><Close /></el-icon>
        </button>
      </div>
    </template>

    <div class="drawer-body">
      <div class="list-head">
        <div class="section-row">
          <div class="section-label">
            订阅应用（{{ subscribedCount }} / {{ apps.length }}）
            <span v-if="q.trim() && visibleApps.length !== apps.length" class="filtered-hint">
              筛出 {{ visibleApps.length }}
            </span>
          </div>
          <div class="bulk-actions">
            <button
              v-if="pendingVisible.length"
              class="bulk-btn"
              :disabled="busy"
              @click="emit('subscribeAll', pendingVisible.map((a) => a.id))"
            >
              {{ busy ? '处理中…' : `${q.trim() ? '订阅匹配' : '全部订阅'} +${pendingVisible.length}` }}
            </button>
            <button
              v-if="subscribedVisible.length"
              class="bulk-btn danger"
              :disabled="busy"
              @click="emit('unsubscribeAll', subscribedVisible.map((a) => a.id))"
            >
              {{ busy ? '处理中…' : `${q.trim() ? '取消匹配' : '全部取消'} -${subscribedVisible.length}` }}
            </button>
          </div>
        </div>

        <label class="dw-search">
          <el-icon :size="13"><Search /></el-icon>
          <input
            v-model="q"
            placeholder="搜应用名或 code"
            spellcheck="false"
            @keydown.esc="q = ''"
          />
          <button v-if="q" class="dw-search-x" title="清空（Esc）" @click.prevent="q = ''">
            <el-icon :size="11"><Close /></el-icon>
          </button>
        </label>
      </div>

      <div class="app-list">
        <div
          v-for="app in visibleApps"
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
        <div v-if="!visibleApps.length" class="dw-empty">
          没有匹配的应用，换个关键词试试。
        </div>
      </div>

      <div class="drawer-note">
        <el-icon :size="14"><InfoFilled /></el-icon>
        <span>订阅只建立引用，不复制变量；取消订阅不会删除任何已配置的值。</span>
      </div>
    </div>

    <template #footer>
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
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
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
  /** 批量订阅/取消订阅只作用于「当前列表里可见的应用」，搜索时即匹配项 */
  (e: 'subscribeAll', appIds: Id[]): void
  (e: 'unsubscribeAll', appIds: Id[]): void
  (e: 'edit'): void
  (e: 'remove'): void
  (e: 'open'): void
}>()

const q = ref('')

/** 搜索只影响列表与批量操作的**作用范围**，不影响顶部 (已订阅/总数) 的统计口径 */
const visibleApps = computed(() => {
  const kw = q.value.trim().toLowerCase()
  if (!kw) return props.apps
  return props.apps.filter(
    (a) =>
      (a.name || '').toLowerCase().includes(kw) ||
      (a.code || '').toLowerCase().includes(kw)
  )
})

const subscribedVisible = computed(() => visibleApps.value.filter((a) => isSub(a)))
const pendingVisible = computed(() => visibleApps.value.filter((a) => !isSub(a)))

const subscribedCount = computed(
  () => props.apps.filter((a) => isSub(a)).length
)

function isSub(app: BoardApp) {
  return (app.subscribedModules || []).some((m) => m.id === props.module?.id)
}
</script>

<style scoped>
/* 头部放进 el-drawer 的 header 插槽：标题与关闭按钮不随列表滚动丢失，
   分割线与内边距由全局 .el-drawer__header 提供，这里只管排布 */
.drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  min-width: 0;
}

.dh-main {
  min-width: 0;
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
  padding-top: 0;
}

/* 顶栏与搜索吸顶：应用一多，批量按钮不能滚走 */
.list-head {
  position: sticky;
  top: 0;
  z-index: 2;
  padding-top: 2px;
  padding-bottom: 8px;
  margin-bottom: 4px;
  background: var(--surface);
}

.section-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.bulk-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
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

/* 批量取消是破坏性操作，视觉上要和「订阅」区分开 */
.bulk-btn.danger {
  border-color: #fbd5d5;
  background: var(--danger-50);
  color: var(--danger);
}

.bulk-btn.danger:hover:not(:disabled) {
  border-color: var(--danger);
  background: var(--danger-50);
}

.filtered-hint {
  margin-left: 6px;
  font-weight: 500;
  color: var(--primary-600);
  letter-spacing: 0;
}

.dw-search {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 8px;
  border: 1px solid var(--border-2);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--text-3);
}

.dw-search:focus-within {
  border-color: var(--primary);
  background: var(--surface);
}

.dw-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  font: inherit;
  font-size: 12.5px;
  color: var(--text);
}

.dw-search input::placeholder {
  color: var(--text-3);
}

.dw-search-x {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  border: 0;
  border-radius: 3px;
  background: var(--surface-3);
  color: var(--text-3);
  cursor: pointer;
}

.dw-search-x:hover {
  color: var(--text);
}

.dw-empty {
  padding: 22px 8px;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-3);
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

/* 页脚放进 el-drawer 的 footer 插槽（在滚动容器之外），
   不再用 sticky + 负 margin 把它压在列表上：那样最后一行会从页脚底下穿出来，
   而列表里的 .el-checkbox__inner 自带 z-index:1，会盖在页脚背景上变成一个白方块 */
.drawer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.foot-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
