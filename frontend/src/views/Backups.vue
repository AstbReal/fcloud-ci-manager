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
        <ProtoIcon name="archive" :size="15" />立即备份
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
              <th style="width: 96px">大小</th>
              <th style="width: 84px">类型</th>
              <th style="width: 150px">生成时间</th>
              <th style="width: 80px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in records" :key="row.id">
              <td class="mono file">{{ row.fileName }}</td>
              <td class="num muted">{{ formatSize(row.size) }}</td>
              <td>
                <span class="tag" :class="row.type === 'MANUAL' ? 'tag-shared' : 'tag-plain'">
                  {{ row.type === 'MANUAL' ? '手动' : '定时' }}
                </span>
              </td>
              <td class="muted num">{{ fmt(row.createdAt) }}</td>
              <td>
                <button class="btn-ghost" @click="download(row)">下载</button>
              </td>
            </tr>
            <tr v-if="!records.length">
              <td colspan="5" class="empty-row">
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
            :disabled="!canRestore"
            @click="doRestore"
          >
            开始恢复
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
}

const records = ref<BackupRow[]>([])
const backing = ref(false)
const restoring = ref(false)
const strategy = ref<'ABORT' | 'MERGE' | 'OVERWRITE'>('ABORT')
const overwriteConfirm = ref('')
const restoreFile = ref<File | null>(null)
const dragOver = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

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

async function createBackup() {
  backing.value = true
  try {
    await api.post('/api/console/admin/backups')
    ElMessage.success('备份完成')
    await load()
  } finally {
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
    const res = await api.upload<{ strategy: string; records: number; files: number }>(
      '/api/console/admin/backups/restore',
      form
    )
    ElMessage.success(
      `恢复完成：策略 ${res.strategy}，${res.records} 条记录，${res.files} 个文件`
    )
    overwriteConfirm.value = ''
    restoreFile.value = null
    await load()
  } finally {
    restoring.value = false
  }
}

onMounted(load)
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
