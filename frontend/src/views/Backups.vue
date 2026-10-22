<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">备份恢复</h2>
        <div class="fc-page-subtitle">
          每日 02:00 自动备份，保留最近 3 份；包内加密变量为密文，不含主密钥。
        </div>
      </div>
      <button class="btn btn-primary" :disabled="backing" @click="createBackup">
        <ProtoIcon name="archive" :size="15" />{{ backing ? '备份中…' : '立即备份' }}
      </button>
    </div>

    <div class="split">
      <div class="card">
        <div class="card-head">
          <div class="card-title">
            备份记录<span class="n">{{ records.length }}</span>
          </div>
        </div>
        <table class="bk-table">
          <thead>
            <tr>
              <th>文件名</th>
              <th style="width: 90px">大小</th>
              <th style="width: 76px">类型</th>
              <th style="width: 88px">状态</th>
              <th style="width: 150px">生成时间</th>
              <th style="width: 72px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in records" :key="row.id">
              <td class="mono file" :title="row.errorMessage || row.fileName">
                {{ row.fileName }}
              </td>
              <td class="num muted">
                {{ row.status === 'RUNNING' ? '—' : formatSize(row.size) }}
              </td>
              <td>
                <span class="tag" :class="row.type === 'MANUAL' ? 'tag-shared' : 'tag-plain'">
                  {{ row.type === 'MANUAL' ? '手动' : '定时' }}
                </span>
              </td>
              <td>
                <span
                  class="tag"
                  :class="statusClass(row.status)"
                  :title="row.errorMessage || ''"
                >
                  {{ statusLabel(row.status) }}
                </span>
              </td>
              <td class="muted num">{{ fmt(row.createdAt) }}</td>
              <td>
                <button
                  class="btn-ghost"
                  :disabled="row.status !== 'SUCCESS'"
                  @click="download(row)"
                >
                  下载
                </button>
              </td>
            </tr>
            <tr v-if="!records.length">
              <td colspan="6" class="empty-row">
                <el-icon :size="26" color="#c3c9d4"><Box /></el-icon>
                <div>还没有备份，点右上「立即备份」生成第一份。</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <div class="card-head">
          <div class="card-title">从备份恢复</div>
        </div>
        <div class="card-body">
          <div
            class="drop-zone"
            :class="{ on: dragOver, filled: !!restoreFile }"
            @click="pickFile"
            @dragover.prevent="dragOver = true"
            @dragleave.prevent="dragOver = false"
            @drop.prevent="onDrop"
          >
            <el-icon :size="22" color="#c3c9d4"><FolderOpened /></el-icon>
            <div v-if="!restoreFile" class="drop-text">
              拖拽 .tar.gz 到此处，或点击选择
            </div>
            <div v-else class="drop-text">
              <b class="mono">{{ restoreFile.name }}</b>
              <span class="drop-size">{{ formatSize(restoreFile.size) }} · 点击更换</span>
            </div>
          </div>
          <input ref="fileInput" type="file" accept=".gz,.tgz,.tar.gz" hidden @change="onPick" />

          <div class="strategy-title">恢复策略</div>
          <div class="strategy-list">
            <button
              v-for="s in strategies"
              :key="s.value"
              class="strategy"
              :class="{ on: strategy === s.value, danger: s.danger }"
              @click="strategy = s.value"
            >
              <span class="radio" :class="{ on: strategy === s.value }" />
              <span class="s-main">
                <span class="s-name">{{ s.label }}</span>
                <span class="s-desc">{{ s.desc }}</span>
              </span>
            </button>
          </div>

          <el-input
            v-if="strategy === 'OVERWRITE'"
            v-model="overwriteConfirm"
            class="confirm-input"
            placeholder="输入 OVERWRITE 确认"
          />

          <div class="callout">
            <el-icon :size="14"><WarningFilled /></el-icon>
            <span>换过主密钥的旧备份，加密变量无法解密。</span>
          </div>

          <el-button
            type="primary"
            style="width: 100%; margin-top: 12px"
            :loading="restoring"
            :disabled="!canRestore || restoring"
            @click="doRestore"
          >
            {{ restoring ? '恢复中…' : '开始恢复' }}
          </el-button>

          <div v-if="lastRestore" class="restore-last">
            <div class="rl-head">
              <span class="tag" :class="statusClass(lastRestore.status)">
                {{ statusLabel(lastRestore.status) }}
              </span>
              <span class="rl-meta">
                {{ lastRestore.strategy }} · {{ fmt(lastRestore.finishedAt || lastRestore.createdAt) }}
              </span>
            </div>
            <div v-if="lastRestore.status === 'SUCCESS'" class="rl-detail">
              {{ lastRestore.records ?? 0 }} 条记录 · {{ lastRestore.files ?? 0 }} 个文件
            </div>
            <div
              v-else-if="lastRestore.status === 'FAILED'"
              class="rl-detail err"
              :title="lastRestore.errorMessage || ''"
            >
              {{ lastRestore.errorMessage || '恢复失败' }}
            </div>
            <div v-else class="rl-detail">正在解包导入，完成后这里会显示结果。</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Download,
  Box,
  WarningFilled,
  FolderOpened
} from '@element-plus/icons-vue'
import { api, saveBlob } from '@/api/http'
import ProtoIcon from '@/components/ProtoIcon.vue'
import type { Id } from '@/types/id'

interface BackupRow {
  id: Id
  fileName: string
  filePath: string
  size: number
  type: string
  createdAt: string
  /** RUNNING | SUCCESS | FAILED；老记录没有该字段时按 SUCCESS 处理 */
  status?: string
  errorMessage?: string
  finishedAt?: string
}

interface RestoreJobRow {
  id: Id
  fileName: string
  strategy: string
  status: string
  records?: number
  files?: number
  errorMessage?: string
  createdAt: string
  finishedAt?: string
}

const records = ref<BackupRow[]>([])
const restoreJobs = ref<RestoreJobRow[]>([])
const backing = ref(false)
const restoring = ref(false)
const strategy = ref<'ABORT' | 'MERGE' | 'OVERWRITE'>('ABORT')
const overwriteConfirm = ref('')
const restoreFile = ref<File | null>(null)
const dragOver = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const lastRestore = computed<RestoreJobRow | null>(() => restoreJobs.value[0] || null)

const strategies = [
  { value: 'ABORT' as const, label: 'ABORT（默认）', desc: '发现冲突整体回滚并列出冲突项' },
  { value: 'MERGE' as const, label: 'MERGE', desc: '按自然键跳过已存在，仅补缺失' },
  {
    value: 'OVERWRITE' as const,
    label: 'OVERWRITE',
    desc: '清空业务表后全量导入，需输入确认词',
    danger: true
  }
]

const canRestore = computed(() => {
  if (!restoreFile.value) return false
  if (strategy.value === 'OVERWRITE') return overwriteConfirm.value === 'OVERWRITE'
  return true
})

function fmt(v?: string) {
  return v ? new Date(v).toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-') : '—'
}

function formatSize(n?: number) {
  if (!n) return '—'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

async function load() {
  records.value = await api.get('/api/console/admin/backups')
}

function statusOf(row: BackupRow) {
  return row.status || 'SUCCESS'
}

function statusLabel(status?: string) {
  if (status === 'RUNNING') return '进行中'
  if (status === 'FAILED') return '失败'
  return '成功'
}

function statusClass(status?: string) {
  if (status === 'RUNNING') return 'tag-warn'
  if (status === 'FAILED') return 'tag-danger'
  return 'tag-ok'
}

// ---- 备份任务轮询 ----
let pollTimer: number | null = null
let pollTicks = 0

function stopPoll() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function pollOnce(targetId?: Id) {
  pollTicks++
  try {
    await load()
  } catch {
    // 单次请求失败不终止轮询，下一轮再试
    return
  }
  if (records.value.some((r) => statusOf(r) === 'RUNNING')) {
    if (pollTicks > 900) {
      // 30 分钟还没结束，大概率后端已经异常，停止轮询避免无限打接口
      stopPoll()
      backing.value = false
      ElMessage.warning('备份任务长时间未结束，请查看服务端日志')
    }
    return
  }
  stopPoll()
  backing.value = false
  const target = records.value.find((r) => String(r.id) === String(targetId))
  if (!target) return
  if (statusOf(target) === 'SUCCESS') {
    ElMessage.success(`备份完成：${formatSize(target.size)}`)
  } else if (statusOf(target) === 'FAILED') {
    ElMessage.error(`备份失败：${target.errorMessage || '未知原因'}`)
  }
}

function startPoll(targetId?: Id) {
  stopPoll()
  pollTicks = 0
  backing.value = true
  pollTimer = window.setInterval(() => pollOnce(targetId), 2000)
}

async function createBackup() {
  backing.value = true
  try {
    const rec = await api.post<BackupRow>('/api/console/admin/backups')
    ElMessage.success('备份任务已提交，正在后台打包…')
    startPoll(rec.id)
  } catch {
    backing.value = false
  }
}

async function download(row: BackupRow) {
  const blob = await api.download(`/api/console/admin/backups/${row.id}/download`)
  saveBlob(blob, row.fileName)
}

function pickFile() {
  fileInput.value?.click()
}

function onPick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (f) restoreFile.value = f
}

function onDrop(e: DragEvent) {
  dragOver.value = false
  const f = e.dataTransfer?.files?.[0]
  if (!f) return
  if (!/\.(tar\.gz|tgz|gz)$/i.test(f.name)) {
    ElMessage.warning('只接受 .tar.gz 备份包')
    return
  }
  restoreFile.value = f
}

async function doRestore() {
  if (!restoreFile.value) return
  restoring.value = true
  try {
    const form = new FormData()
    form.append('file', restoreFile.value)
    form.append('strategy', strategy.value)
    // 上传即返回任务 id，解包导入在后台跑；只等上传本身，30 分钟足够大包
    const job = await api.upload<RestoreJobRow>(
      '/api/console/admin/backups/restore',
      form,
      1800000
    )
    ElMessage.success('恢复任务已提交，正在后台执行…')
    overwriteConfirm.value = ''
    restoreFile.value = null
    startRestorePoll(job.id)
  } catch {
    restoring.value = false
  }
}

// ---- 恢复任务轮询 ----
let restoreTimer: number | null = null
let restoreTicks = 0

function stopRestorePoll() {
  if (restoreTimer !== null) {
    window.clearInterval(restoreTimer)
    restoreTimer = null
  }
}

async function loadRestores() {
  restoreJobs.value = await api.get('/api/console/admin/backups/restores')
}

async function pollRestoreOnce(targetId?: Id) {
  restoreTicks++
  try {
    await loadRestores()
  } catch {
    return
  }
  const target = restoreJobs.value.find((j) => String(j.id) === String(targetId))
  if (target && target.status === 'RUNNING') {
    if (restoreTicks > 1800) {
      stopRestorePoll()
      restoring.value = false
      ElMessage.warning('恢复任务长时间未结束，请查看服务端日志')
    }
    return
  }
  stopRestorePoll()
  restoring.value = false
  await load()
  if (!target) return
  if (target.status === 'SUCCESS') {
    ElMessage.success(
      `恢复完成：${target.records ?? 0} 条记录，${target.files ?? 0} 个文件`
    )
  } else if (target.status === 'FAILED') {
    ElMessage.error(`恢复失败：${target.errorMessage || '未知原因'}`)
  }
}

function startRestorePoll(targetId?: Id) {
  stopRestorePoll()
  restoreTicks = 0
  restoring.value = true
  restoreTimer = window.setInterval(() => pollRestoreOnce(targetId), 2000)
}

onMounted(async () => {
  await Promise.all([load(), loadRestores()])
  // 刷新页面时如果还有任务在跑，接着轮询
  if (records.value.some((r) => statusOf(r) === 'RUNNING')) {
    startPoll()
  }
  if (restoreJobs.value.some((j) => j.status === 'RUNNING')) {
    startRestorePoll()
  }
})

onBeforeUnmount(() => {
  stopPoll()
  stopRestorePoll()
})
</script>

<style scoped>
.split {
  display: grid;
  grid-template-columns: 1fr 380px;
  gap: 16px;
  align-items: start;
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--border);
}

.card-title {
  font-size: 13.5px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 7px;
}

.card-title .n {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-3);
  background: var(--surface-3);
  border-radius: 5px;
  padding: 0 6px;
  line-height: 17px;
}

.card-body {
  padding: 14px 16px;
}

.bk-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.bk-table th {
  height: 36px;
  padding: 0 16px;
  text-align: left;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-2);
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
}

.bk-table td {
  height: 44px;
  padding: 0 16px;
  font-size: 13px;
  border-bottom: 1px solid #f0f1f5;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bk-table tbody tr:hover {
  background: #fbfcfe;
}

.bk-table .file {
  font-size: 12px;
}

.bk-table .muted {
  color: var(--text-3);
}

.bk-table .num {
  font-variant-numeric: tabular-nums;
}

.empty-row {
  height: 160px;
  text-align: center;
  color: var(--text-3);
  font-size: 12.5px;
  line-height: 2;
}

.tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border-radius: 5px;
  font-size: 11.5px;
  font-weight: 500;
  line-height: 1;
}

.tag-plain {
  background: var(--surface-3);
  color: var(--text-2);
}

.tag-shared {
  background: #f5f3ff;
  color: #6d28d9;
  border: 1px solid #e9e4ff;
}

.btn-ghost {
  height: 28px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--primary);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}

.btn-ghost:hover {
  background: var(--surface-3);
}

.btn-ghost:disabled {
  color: var(--text-3);
  background: transparent;
  cursor: not-allowed;
}

.restore-last {
  margin-top: 12px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
}

.rl-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.rl-meta {
  font-size: 11.5px;
  color: var(--text-3);
}

.rl-detail {
  margin-top: 5px;
  overflow: hidden;
  font-size: 11.5px;
  color: var(--text-2);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rl-detail.err {
  color: var(--danger);
}

.drop-zone {
  border: 1.5px dashed var(--border-2);
  border-radius: var(--r);
  padding: 22px;
  text-align: center;
  color: var(--text-3);
  font-size: 12.5px;
  cursor: pointer;
  transition: 0.14s;
}

.drop-zone:hover,
.drop-zone.on {
  border-color: var(--primary);
  background: var(--primary-50);
}

.drop-zone.filled {
  border-style: solid;
  border-color: var(--primary-100);
  background: var(--primary-50);
}

.drop-text {
  margin-top: 8px;
  line-height: 1.6;
}

.drop-text b {
  display: block;
  font-size: 12px;
  color: var(--text);
  word-break: break-all;
}

.drop-size {
  display: block;
  font-size: 11.5px;
  color: var(--text-3);
  margin-top: 2px;
}

.strategy-title {
  margin-top: 16px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text);
}

.strategy-list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.strategy {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 8px 9px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  background: transparent;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
}

.strategy:hover {
  background: var(--surface-2);
}

.strategy.on {
  border-color: var(--border);
  background: var(--surface-2);
}

.radio {
  width: 14px;
  height: 14px;
  flex: 0 0 14px;
  margin-top: 2px;
  border: 1px solid var(--border-2);
  border-radius: 50%;
  background: #fff;
}

.radio.on {
  border: 4px solid var(--primary);
}

.s-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.s-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
}

.strategy.danger .s-name {
  color: var(--danger);
}

.s-desc {
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.5;
}

.confirm-input {
  margin-top: 10px;
  font-family: var(--mono);
}

.callout {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--warn-50);
  border: 1px solid #fce6b5;
  color: var(--warn);
  font-size: 12px;
  line-height: 1.55;
}

@media (max-width: 1100px) {
  .split {
    grid-template-columns: 1fr;
  }
}
</style>
