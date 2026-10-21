<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">用户管理</h2>
        <div class="fc-page-subtitle">
          全局角色仅区分系统管理员 / 普通用户；模块权限在模块内授权。
        </div>
      </div>
      <div class="ph-actions">
        <label class="field search-field">
          <ProtoIcon name="search" :size="14" style="color: #8b93a1" />
          <input v-model="keyword" placeholder="搜索姓名 / 邮箱" @input="debouncedLoad" />
        </label>
      </div>
    </div>

    <div class="card">
      <table>
        <thead>
          <tr>
            <th>用户</th>
            <th style="width: 160px">GitLab</th>
            <th style="width: 140px">全局角色</th>
            <th style="width: 100px">参与模块</th>
            <th style="width: 150px">最后登录</th>
            <th style="width: 110px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.id">
            <td>
              <div class="user-cell">
                <div class="avatar" :style="avatarStyle(u)">
                  {{ (u.nickname || u.name || '?').charAt(0) }}
                </div>
                <div>
                  <div class="u-name">{{ u.nickname || u.name || '—' }}</div>
                  <div class="u-mail">{{ u.email }}</div>
                </div>
              </div>
            </td>
            <td class="mono muted">{{ u.gitlabId || '—' }}</td>
            <td>
              <span
                class="tag"
                :class="u.globalRole === 'SYSTEM_ADMIN' ? 'tag-shared' : 'tag-plain'"
              >
                {{ u.globalRole === 'SYSTEM_ADMIN' ? '系统管理员' : '普通用户' }}
              </span>
            </td>
            <td class="num">{{ u.memberCount ?? 0 }}</td>
            <td class="muted">{{ relative(u.lastLoginAt) }}</td>
            <td>
              <el-dropdown
                trigger="click"
                :disabled="u.id === myId"
                @command="(r: string) => setRole(u, r)"
              >
                <button class="btn btn-ghost btn-sm" :disabled="u.id === myId">
                  改角色
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="SYSTEM_ADMIN">设为系统管理员</el-dropdown-item>
                    <el-dropdown-item command="NONE">设为普通用户</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </td>
          </tr>
          <tr v-if="!users.length">
            <td colspan="6" class="empty-row">
              {{ keyword ? '没有匹配的用户，换个关键词试试。' : '还没有用户登录过。' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userApi, type UserItem } from '@/api'
import ProtoIcon from '@/components/ProtoIcon.vue'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const users = ref<UserItem[]>([])
const keyword = ref('')
const myId = computed(() => store.user?.id)

let timer: ReturnType<typeof setTimeout> | null = null
function debouncedLoad() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(load, 300)
}

async function load() {
  users.value = await userApi.list(keyword.value || undefined)
}

function avatarStyle(u: UserItem) {
  const seed = (u.email || u.name || '?').charCodeAt(0) % 4
  const palettes = [
    { background: '#eef7f2', color: '#0f766e', borderColor: '#d3ece1' },
    { background: '#fdf4ff', color: '#86198f', borderColor: '#f5d0fe' },
    { background: '#fff7ed', color: '#9a3412', borderColor: '#fed7aa' },
    { background: '#e8edf7', color: '#3b5b9a', borderColor: '#d4dcee' }
  ]
  return palettes[seed]
}

function relative(v?: string | null) {
  if (!v) return '从未登录'
  const diff = Date.now() - new Date(v).getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day === 1) return '昨天'
  if (day < 30) return `${day} 天前`
  return new Date(v).toLocaleDateString('zh-CN').replace(/\//g, '-')
}

async function setRole(row: UserItem, role: string) {
  if (role === row.globalRole) return
  await userApi.setRole(row.id, role)
  ElMessage.success('角色已更新（对方重新登录后生效）')
  await load()
}

onMounted(load)
</script>

<style scoped>
.ph-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  padding-top: 2px;
}

.search-field {
  width: 240px;
  gap: 7px;
}

.search-field input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  font: inherit;
  font-size: 13px;
  color: var(--text);
}

.search-field input::placeholder {
  color: var(--text-3);
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
}

table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
}

th {
  text-align: left;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-2);
  letter-spacing: 0.02em;
  background: var(--surface-2);
  padding: 0 12px;
  height: 36px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

td {
  padding: 0 12px;
  height: 52px;
  border-bottom: 1px solid #f0f1f5;
  font-size: 13px;
  vertical-align: middle;
}

tbody tr:last-child td {
  border-bottom: 0;
}

tbody tr:hover td {
  background: #fbfcfe;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 9px;
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 99px;
  border: 1px solid #d4dcee;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 600;
  flex: 0 0 28px;
}

.u-name {
  font-weight: 500;
  color: var(--text);
}

.u-mail {
  font-size: 11.5px;
  color: var(--text-3);
}

.mono {
  font-family: var(--mono);
  font-size: 12.5px;
}

.muted {
  color: var(--text-3);
}

.num {
  font-variant-numeric: tabular-nums;
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
  white-space: nowrap;
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

.empty-row {
  height: 100px;
  text-align: center;
  color: var(--text-3);
  font-size: 12.5px;
}
</style>
