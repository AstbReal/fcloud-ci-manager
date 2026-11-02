<template>
  <div class="cb">
    <div class="cb-head">
      <span v-if="label" class="cb-name mono">{{ label }}</span>
      <span class="cb-meta">{{ lineCount }} 行</span>
      <div class="cb-extra"><slot name="actions" /></div>
      <button
        v-if="showCopy"
        class="cb-copy"
        :class="state"
        type="button"
        :title="state === 'done' ? '已复制到剪贴板' : '复制全部内容'"
        @click="onCopy"
      >
        <ProtoIcon :name="state === 'done' ? 'check' : 'copy'" :size="13" />
        <span>{{ state === 'done' ? '已复制' : state === 'fail' ? '复制失败' : '复制' }}</span>
      </button>
    </div>
    <div ref="bodyRef" class="cb-body" :style="{ maxHeight }">
      <div v-for="(tokens, i) in rows" :key="i" class="cb-line">
        <span class="cb-ln">{{ i + 1 }}</span>
        <code class="cb-lc" :class="{ wrap }">
          <span v-for="(tk, ti) in tokens" :key="ti" :class="tk.c ? 'tk-' + tk.c : null">{{
            tk.t
          }}</span>
        </code>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ProtoIcon from '@/components/ProtoIcon.vue'
import { COPY_FAIL_HINT, copyText, selectTextIn } from '@/utils/clipboard'
import { highlightLine, stripCommentLines, type Lang } from '@/utils/codeHighlight'

/**
 * 只读代码块：行号 + 轻量高亮 + 一键复制。
 * 高亮完全走 textContent 插值，不拼 HTML，不存在注入面。
 *
 * lang='text' 用于 URL / 纯文本：不做记号识别，避免把端口号当数字染色。
 */
const props = withDefaults(
  defineProps<{
    code: string
    lang?: Lang | 'text'
    label?: string
    /** 正文最大高度，超出滚动；默认 52vh 保证抽屉里也能露出页脚 */
    maxHeight?: string
    /** 长 URL / 长行折行显示，默认横向滚动 */
    wrap?: boolean
    showCopy?: boolean
    /** 复制时去掉 # 注释行（页面仍显示，便于阅读） */
    stripComments?: boolean
  }>(),
  { lang: 'kv', label: '', maxHeight: '52vh', wrap: false, showCopy: true, stripComments: false }
)

const emit = defineEmits<{ copied: [] }>()

const lines = computed(() => {
  const src = props.code.replace(/\r\n/g, '\n').replace(/\n+$/, '')
  return src ? src.split('\n') : ['']
})

const lineCount = computed(() => lines.value.length)

const rows = computed(() =>
  lines.value.map((line) =>
    props.lang === 'text' ? [{ t: line }] : highlightLine(line, props.lang)
  )
)

const state = ref<'idle' | 'done' | 'fail'>('idle')
const bodyRef = ref<HTMLElement | null>(null)
let timer: number | undefined

async function onCopy() {
  const payload = props.stripComments ? stripCommentLines(props.code) : props.code
  const ok = await copyText(payload)
  state.value = ok ? 'done' : 'fail'
  if (ok) {
    emit('copied')
  } else if (selectTextIn(bodyRef.value)) {
    // HTTP 非安全上下文里 execCommand 也可能被策略挡住：直接把内容选中兜底
    ElMessage.warning({ message: '浏览器拒绝写剪贴板，已选中整段文本，按 Ctrl+C 复制', duration: 6000 })
  } else {
    ElMessage.warning({ message: COPY_FAIL_HINT, duration: 6000 })
  }
  window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    state.value = 'idle'
  }, 1600)
}

onBeforeUnmount(() => window.clearTimeout(timer))
</script>

<style scoped>
.cb {
  border: 1px solid var(--border);
  border-radius: var(--r);
  background: var(--surface);
  overflow: hidden;
}

.cb-head {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 6px 0 12px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
}

.cb-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cb-meta {
  font-size: 11px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.cb-extra {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
}

/* 只有 actions 插槽缺省时才让复制按钮顶到右边 */
.cb-extra:empty {
  margin-left: auto;
}

.cb-copy {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 24px;
  padding: 0 8px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text-3);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: 0.12s;
}

.cb-copy:hover {
  background: var(--surface-3);
  color: var(--text);
}

.cb-copy.done {
  color: var(--ok);
  background: var(--ok-50);
}

.cb-copy.fail {
  color: var(--danger);
  background: var(--danger-50);
}

.cb-body {
  overflow: auto;
  padding: 8px 0;
  background: #fcfcfd;
  font-family: var(--mono);
  /* 13px / 21px 固定行高：中文注释混排时基线不跳 */
  font-size: 13px;
  line-height: 21px;
  /* 关掉等宽连字，否则 => 会显示成 ⇒，和原文对不上 */
  font-variant-ligatures: none;
  font-feature-settings: "calt" 0, "liga" 0, "clig" 0;
  font-variant-numeric: tabular-nums;
  -moz-tab-size: 2;
  tab-size: 2;
}

.cb-line {
  display: flex;
  align-items: flex-start;
  min-height: 21px;
  padding-left: 8px;
}

.cb-line:hover {
  background: #f2f5fb;
}

.cb-ln {
  flex: 0 0 32px;
  padding-right: 12px;
  text-align: right;
  color: #9aa3b2;
  font-size: 11px;
  line-height: 21px;
  font-variant-numeric: tabular-nums;
  /* 行号不进选区，复制粘贴不会带出来 */
  user-select: none;
}

.cb-lc {
  flex: 1;
  padding-right: 14px;
  white-space: pre;
  color: var(--text);
}

.cb-lc.wrap {
  white-space: pre-wrap;
  word-break: break-all;
}

.tk-key {
  color: #1d4ed8;
}

.tk-str {
  color: #047857;
}

.tk-num,
.tk-bool {
  color: #b45309;
}

.tk-tag,
.tk-var {
  color: #7c3aed;
}

.tk-com {
  color: #5a6472;
}
</style>
