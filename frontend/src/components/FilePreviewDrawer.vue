<template>
  <el-drawer
    :model-value="modelValue"
    size="720px"
    :with-header="true"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @open="onOpen"
    @closed="onClosed"
  >
    <template #header>
      <div class="pv-head">
        <span class="pv-name mono">{{ file?.fileKey }}</span>
        <span class="pv-pill">
          {{ moduleCode }}<template v-if="envName"> › {{ envName }}</template>
        </span>
        <span class="pv-pill info mono">
          v{{ version }}<template v-if="meta?.size"> · {{ fmtSize(meta.size) }}</template>
        </span>
        <div class="pv-head-actions">
          <button class="btn btn-sm" :disabled="!text" @click="copyAll">复制全文</button>
          <button class="btn btn-sm" @click="download">下载</button>
        </div>
      </div>
    </template>

    <div class="pv-bar">
      <el-dropdown trigger="click" placement="bottom-start" @command="onPickVersion">
        <button type="button" class="pv-vbtn">
          <span class="mono">v{{ version }}</span>
          <ProtoIcon name="chevron" :size="12" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="v in versionsDesc" :key="v.version" :command="v.version">
              <span class="pv-vi">
                <b class="mono">v{{ v.version }}</b>
                <span class="pv-vm">{{ fmtSize(v.size) }} · {{ fmtDay(v.createdAt) }}</span>
              </span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <label v-if="canDiff && !editing" class="pv-check">
        <input v-model="diffOn" type="checkbox" @change="render" />
        <span class="pv-box" :class="{ on: diffOn }">
          <ProtoIcon name="check" :size="11" />
        </span>
        与上一版对比
      </label>

      <span v-if="showDiff && !editing" class="pv-stat">
        <b v-if="added" class="add">+{{ added }}</b>
        <b v-if="removed" class="del">−{{ removed }}</b>
      </span>

      <div class="pv-actions">
        <button class="btn btn-sm" @click="copyCurl">复制 curl</button>
        <button
          v-if="text && !binary && !tooLarge && !editing"
          class="btn btn-sm"
          @click="startEdit"
        >
          基于 v{{ version }} 修改
        </button>
      </div>
    </div>

    <div v-if="sensitive && !revealed && !loading" class="pv-mask">
      <ProtoIcon name="lock" :size="22" style="color: #98a1b0" />
      <div class="pv-mask-t">这是密钥类文件，内容默认隐藏</div>
      <div class="pv-mask-s">展开会记录一条高危审计（谁、什么时候、看了哪个版本）</div>
      <button class="btn btn-sm btn-primary" @click="reveal">显示内容</button>
    </div>

    <div v-else class="pv-body">
      <div v-if="loading" class="pv-hint">加载中…</div>

      <div v-else-if="error" class="pv-hint err">{{ error }}</div>

      <div v-else-if="tooLarge" class="pv-hint">
        文件超过 {{ Math.round(PREVIEW_MAX / 1024) }}KB，不在页面里预览。
        <button class="link" @click="download">直接下载</button>
      </div>

      <div v-else-if="binary" class="pv-hint">
        二进制文件（{{ fmtSize(meta?.size) }}），无法按文本预览。
        <button class="link" @click="download">直接下载</button>
      </div>

      <template v-else>
        <div v-if="editing" class="pv-edit">
          <textarea v-model="draft" spellcheck="false" class="pv-textarea" />
          <div class="pv-edit-bar">
            <span class="pv-edit-tip">
              保存后生成 v{{ (file?.currentVersion || 0) + 1 }}，当前版本保留
            </span>
            <button class="btn btn-sm" @click="editing = false">取消</button>
            <button class="btn btn-sm btn-primary" :disabled="saving" @click="saveAsNew">
              {{ saving ? '保存中…' : '保存为新版本' }}
            </button>
          </div>
        </div>

        <template v-else>
          <div class="pv-code">
            <template v-for="(b, bi) in blocks" :key="bi">
              <button v-if="b.kind === 'fold'" class="pv-fold" @click="expandAll">
                ⋯ {{ b.rows.length }} 行未变化
              </button>
              <div
                v-for="(row, ri) in b.rows"
                :key="`${bi}-${ri}`"
                class="pv-line"
                :class="showDiff ? row.type : 'plain'"
              >
                <span class="ln">{{ showDiff ? (row.left ?? '') : row.right }}</span>
                <span v-if="showDiff" class="ln">{{ row.right ?? '' }}</span>
                <span v-if="showDiff" class="mark">{{
                  row.type === 'add' ? '+' : row.type === 'del' ? '−' : ''
                }}</span>
                <code class="lc"><span
                  v-for="(tk, ti) in tokensOf(row.text)"
                  :key="ti"
                  :class="tk.c ? `tk-${tk.c}` : ''"
                >{{ tk.t }}</span></code>
              </div>
            </template>
          </div>

          <div class="pv-foot">
            <i class="dot" />
            {{ totalLines }} 行 · 只读渲染，不执行脚本<template v-if="sensitive">
              · 密钥类文件默认遮罩</template>
            <template v-if="truncated"> · 差异只看前 2000 行</template>
          </div>
        </template>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ProtoIcon from '@/components/ProtoIcon.vue'
import { api } from '@/api/http'
import { diffLines, toBlocks, type DiffBlock, type DiffRow } from '@/utils/lineDiff'
import {
  highlightLine,
  isHighlightable,
  isSensitive,
  isTextLike,
  langOf,
  type Token
} from '@/utils/codeHighlight'
import type { Id } from '@/types/id'

interface PreviewFile {
  id: Id
  moduleId: Id
  fileKey: string
  currentVersion: number
}

interface VersionMeta {
  version: number
  size?: number
  mimeType?: string
  originalName?: string
  createdAt?: string
}

const props = defineProps<{
  modelValue: boolean
  file: PreviewFile | null
  envId: Id | null
  envName?: string
  moduleCode?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const PREVIEW_MAX = 512 * 1024
const PLAIN_MAX_LINES = 2000

const versions = ref<VersionMeta[]>([])
const version = ref(1)
const diffOn = ref(true)
const text = ref('')
const baseText = ref('')
const loading = ref(false)
const error = ref('')
const tooLarge = ref(false)
const binary = ref(false)
const revealed = ref(false)
const editing = ref(false)
const draft = ref('')
const saving = ref(false)
const expanded = ref(false)
const diffResult = ref<{ rows: DiffRow[]; added: number; removed: number; truncated: boolean }>({
  rows: [],
  added: 0,
  removed: 0,
  truncated: false
})

const sensitive = computed(() => isSensitive(props.file?.fileKey || ''))
const meta = computed(() => versions.value.find((v) => v.version === version.value))
const versionsDesc = computed(() => [...versions.value].sort((a, b) => b.version - a.version))
const baseVersion = computed(() => {
  const prev = versions.value.find((v) => v.version === version.value - 1)
  return prev ? prev.version : null
})
/** 没有上一版时无从对比：直接按原文渲染，否则 diff 结果为空、正文一片白 */
const canDiff = computed(() => versions.value.length > 1 && baseVersion.value !== null)
const showDiff = computed(() => diffOn.value && canDiff.value)
const added = computed(() => (showDiff.value ? diffResult.value.added : 0))
const removed = computed(() => (showDiff.value ? diffResult.value.removed : 0))
const truncated = computed(() => showDiff.value && diffResult.value.truncated)

const plainRows = computed<DiffRow[]>(() =>
  text.value
    .split('\n')
    .slice(0, PLAIN_MAX_LINES)
    .map((t, i) => ({ type: 'same' as const, text: t, right: i + 1 }))
)
const totalLines = computed(() => (text.value ? text.value.split('\n').length : 0))

const blocks = computed<DiffBlock[]>(() => {
  if (editing.value) return []
  if (!showDiff.value) return [{ kind: 'same', rows: plainRows.value }]
  if (expanded.value) return [{ kind: 'same', rows: diffResult.value.rows }]
  return toBlocks(diffResult.value.rows)
})

/** diff 模式下不做语法着色：红/绿底色上再叠色会糊成一片 */
function tokensOf(line: string): Token[] {
  if (showDiff.value || !props.file || !isHighlightable(props.file.fileKey)) {
    return [{ t: line || ' ' }]
  }
  return line ? highlightLine(line, langOf(props.file.fileKey)) : [{ t: ' ' }]
}

function fmtSize(n?: number) {
  if (!n && n !== 0) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

function fmtDay(v?: string) {
  if (!v) return ''
  const d = new Date(v)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function reset() {
  versions.value = []
  text.value = ''
  baseText.value = ''
  error.value = ''
  tooLarge.value = false
  binary.value = false
  revealed.value = false
  editing.value = false
  draft.value = ''
  expanded.value = false
  diffResult.value = { rows: [], added: 0, removed: 0, truncated: false }
}

async function onOpen() {
  if (!props.file) return
  reset()
  diffOn.value = true
  loading.value = true
  try {
    versions.value = await api.get<VersionMeta[]>(
      `/api/console/files/${props.file.id}/versions`
    )
    version.value = versions.value.reduce((m, v) => Math.max(m, v.version), 1)
    await loadVersion()
  } catch {
    error.value = '无法读取版本信息'
  } finally {
    loading.value = false
  }
}

function onClosed() {
  reset()
}

function onPickVersion(v: number) {
  version.value = v
  void loadVersion()
}

async function fetchText(v: number) {
  const res = await fetch(`/api/console/files/${props.file!.id}/download?version=${v}`, {
    credentials: 'same-origin'
  })
  if (!res.ok) throw new Error('下载失败')
  return res.text()
}

async function loadVersion() {
  if (!props.file) return
  loading.value = true
  error.value = ''
  text.value = ''
  baseText.value = ''
  tooLarge.value = false
  binary.value = false
  expanded.value = false
  editing.value = false

  const m = versions.value.find((v) => v.version === version.value)
  if (!isTextLike(props.file.fileKey, m?.mimeType)) {
    binary.value = true
    loading.value = false
    return
  }
  if ((m?.size ?? 0) > PREVIEW_MAX) {
    tooLarge.value = true
    loading.value = false
    return
  }

  try {
    text.value = await fetchText(version.value)
    if (diffOn.value && baseVersion.value) {
      baseText.value = await fetchText(baseVersion.value)
    }
    render()
  } catch {
    error.value = '读取文件内容失败'
  } finally {
    loading.value = false
  }
}

function render() {
  if (!showDiff.value || !baseText.value || !text.value) {
    diffResult.value = { rows: [], added: 0, removed: 0, truncated: false }
    return
  }
  diffResult.value = diffLines(baseText.value, text.value)
}

function expandAll() {
  expanded.value = true
}

async function reveal() {
  if (!props.file) return
  await api.post(`/api/console/files/${props.file.id}/reveal?version=${version.value}`)
  revealed.value = true
}

async function copyAll() {
  try {
    await navigator.clipboard.writeText(text.value)
    ElMessage.success('已复制全文')
  } catch {
    ElMessage.warning('复制失败，请手动选择内容')
  }
}

function download() {
  if (!props.file) return
  window.open(
    `/api/console/files/${props.file.id}/download?version=${version.value}`,
    '_blank'
  )
}

async function copyCurl() {
  if (!props.file) return
  const url = `${location.origin}/api/v1/pipeline/files/${props.file.moduleId}/${props.file.id}?version=${version.value}`
  const cmd = `curl -sS -H "Authorization: Bearer $FCLOUD_TOKEN" \\\n  "${url}" -O -J`
  try {
    await navigator.clipboard.writeText(cmd)
    ElMessage.success('已复制 curl 命令')
  } catch {
    ElMessage.warning('复制失败')
  }
}

function startEdit() {
  draft.value = text.value
  editing.value = true
}

async function saveAsNew() {
  if (!props.file || !props.envId) return
  const remark = `基于 v${version.value} 修订 · ${new Date().toLocaleString('zh-CN', {
    hour12: false
  })}`
  const blob = new Blob([draft.value], { type: 'text/plain;charset=utf-8' })
  const fd = new FormData()
  fd.append('file', blob, props.file.fileKey)
  fd.append('fileKey', props.file.fileKey)
  fd.append('environmentId', String(props.envId))
  fd.append('remark', remark)

  saving.value = true
  try {
    await api.upload(`/api/console/modules/${props.file.moduleId}/files`, fd)
    ElMessage.success('已保存为新版本')
    editing.value = false
    emit('saved')
    await onOpen()
  } finally {
    saving.value = false
  }
}

watch(
  () => props.modelValue,
  (v) => {
    if (!v) reset()
  }
)
</script>

<style scoped>
.pv-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.pv-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pv-pill {
  flex: 0 0 auto;
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--surface-3);
  color: var(--text-2);
  font-size: 11.5px;
  line-height: 1.6;
  white-space: nowrap;
}

.pv-pill.info {
  background: var(--primary-50);
  color: var(--primary-700);
}

.pv-head-actions {
  display: flex;
  gap: 6px;
  margin-left: auto;
}

.pv-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border);
}

.pv-vbtn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--border-2);
  border-radius: 5px;
  background: var(--surface);
  font-family: inherit;
  font-size: 12px;
  color: var(--text-2);
  cursor: pointer;
}

.pv-vbtn:hover {
  border-color: #b9c0cc;
  color: var(--text);
}

.pv-vi {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 168px;
}

.pv-vi b {
  font-size: 12.5px;
  font-weight: 500;
}

.pv-vm {
  font-size: 11.5px;
  color: var(--text-3);
}

.pv-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-2);
  cursor: pointer;
  user-select: none;
}

.pv-check input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.pv-box {
  display: grid;
  place-items: center;
  width: 15px;
  height: 15px;
  border: 1px solid var(--border-2);
  border-radius: 4px;
  color: transparent;
  transition: 0.12s;
}

.pv-box.on {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}

.pv-stat {
  display: flex;
  gap: 8px;
  font-family: var(--mono);
  font-size: 12px;
}

.pv-stat .add {
  color: #27500a;
  font-weight: 500;
}

.pv-stat .del {
  color: #791f1f;
  font-weight: 500;
}

.pv-actions {
  display: flex;
  gap: 6px;
  margin-left: auto;
}

.pv-body {
  margin-top: 12px;
}

.pv-hint {
  padding: 48px 12px;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.7;
}

.pv-hint.err {
  color: var(--danger);
}

.pv-hint .link {
  margin-left: 4px;
  border: 0;
  background: none;
  font: inherit;
  color: var(--primary);
  cursor: pointer;
}

.pv-mask {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  padding: 52px 20px;
  border: 1px dashed var(--border-2);
  border-radius: var(--r);
  background: var(--surface-2);
  text-align: center;
}

.pv-mask-t {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text);
}

.pv-mask-s {
  font-size: 11.5px;
  color: var(--text-3);
  margin-bottom: 6px;
}

.pv-code {
  min-height: 240px;
  max-height: calc(100vh - 250px);
  overflow: auto;
  padding: 10px 0;
  border: 1px solid var(--border);
  border-radius: var(--r);
  background: var(--surface-3);
  font-family: var(--mono);
  /* 代码正文：13px / 21px 固定行高比 1.8 的百分比行高更稳，
     中文注释混排时行基线不会随字号微调而跳动 */
  font-size: 13px;
  line-height: 21px;
  /* 等宽连字（=> ↔ ⇒、!= ↔ ≠）会让原文和屏幕对不上，显式关掉 */
  font-variant-ligatures: none;
  font-feature-settings: "calt" 0, "liga" 0, "clig" 0;
  font-variant-numeric: tabular-nums;
  -moz-tab-size: 4;
  tab-size: 4;
}

.pv-line {
  display: flex;
  align-items: flex-start;
  min-height: 21px;
  padding-left: 12px;
}

.pv-line .ln {
  /* 42px 容得下 4 位行号（预览最多 2000 行），11px 数字 + tabular 对齐 */
  flex: 0 0 42px;
  padding-right: 14px;
  text-align: right;
  color: var(--text-3);
  font-size: 11px;
  line-height: 21px;
  font-variant-numeric: tabular-nums;
  user-select: none;
}

.pv-line .mark {
  flex: 0 0 14px;
  color: var(--text-3);
  font-size: 11px;
  line-height: 21px;
  user-select: none;
}

.pv-line .lc {
  flex: 1;
  padding-right: 16px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text);
}

/* 悬停当前行：读长文件时定位不丢行（差异行保留红绿底，不掺灰） */
.pv-line:not(.add):not(.del):hover {
  background: #e9edf4;
}

.pv-line:hover .ln {
  color: var(--text-2);
}

.pv-line.add {
  background: #eaf3de;
}

.pv-line.del {
  background: #fcebeb;
}

.pv-line.add .ln,
.pv-line.add .mark {
  color: #437010;
}

.pv-line.del .ln,
.pv-line.del .mark {
  color: #a33a3a;
}

.pv-line.add .lc {
  color: #27500a;
}

.pv-line.del .lc {
  color: #791f1f;
}

.pv-fold {
  display: block;
  width: 100%;
  /* 与代码正文左缘对齐：12px 行内边距 + 42px 行号槽 */
  padding: 2px 0 2px 54px;
  margin: 4px 0;
  border: 0;
  border-top: 1px dashed var(--border-2);
  border-bottom: 1px dashed var(--border-2);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 11.5px;
  color: var(--text-3);
  text-align: left;
  cursor: pointer;
}

.pv-fold:hover {
  color: var(--primary);
}

/* 键名 / 指令 / SQL 关键字 */
.tk-key {
  color: #1d4ed8;
}

/* 字符串字面量 */
.tk-str {
  color: #047857;
}

/* 数字 */
.tk-num {
  color: #b45309;
}

/* true / false / null / on：跟着数字一档，比把布尔也染成数字色更诚实 */
.tk-bool {
  color: #b45309;
}

/* YAML 的块标量、锚点与标签；shell 的命令替换 ``...`` */
.tk-tag {
  color: #7c3aed;
}

/* ${VAR} 与其 shell 变量 */
.tk-var {
  color: #7c3aed;
}

/* 注释：在 #f2f4f7 与 #fff 上都 >= 4.5:1 */
.tk-com {
  color: #5a6472;
}

.pv-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 10px 2px 0;
  font-size: 11.5px;
  color: var(--text-3);
}

.pv-foot .dot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
  background: #c3c9d4;
}

.pv-edit {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pv-textarea {
  width: 100%;
  height: calc(100vh - 320px);
  min-height: 320px;
  padding: 12px;
  border: 1px solid var(--border-2);
  border-radius: var(--r);
  background: var(--surface);
  font-family: var(--mono);
  /* 与只读预览同一套度量，切换编辑态时字符位置不跳 */
  font-size: 13px;
  line-height: 21px;
  font-variant-ligatures: none;
  font-feature-settings: "calt" 0, "liga" 0, "clig" 0;
  -moz-tab-size: 4;
  tab-size: 4;
  color: var(--text);
  outline: none;
  resize: vertical;
}

.pv-textarea:focus {
  border-color: var(--primary);
}

.pv-edit-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pv-edit-tip {
  margin-right: auto;
  font-size: 11.5px;
  color: var(--text-3);
}
</style>
