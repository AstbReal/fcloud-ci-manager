<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">
          <ProtoIcon name="cloud" :size="15" />
        </div>
        <div class="brand-text">
          <div class="brand-name">fcloud-ci-hub</div>
          <div class="brand-sub">配置分发中心</div>
        </div>
      </div>

      <nav class="nav">
        <template v-for="group in menuGroups" :key="group.label">
          <div class="nav-label">{{ group.label }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ active: isActive(item.path) }"
            :title="item.label"
          >
            <ProtoIcon :name="item.icon" />
            <span class="nav-text">{{ item.label }}</span>
            <span v-if="item.path === '/' && todoCount > 0" class="nav-count">
              {{ todoCount }}
            </span>
          </router-link>
        </template>
      </nav>

      <div class="sidebar-foot">
        <span class="health-dot" :class="health" />
        <span class="health-text">{{ healthText }}</span>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="crumb-home" @click="router.push('/')">fcloud-ci-hub</span>
          <span class="sep">/</span>
          <b>{{ currentTitle }}</b>
        </div>

        <div class="topbar-right">
          <el-dropdown trigger="click" @command="onCommand">
            <span class="user-trigger">
              <el-avatar :size="28" :src="user?.avatarUrl">
                {{ (user?.nickname || user?.name || '?').charAt(0) }}
              </el-avatar>
              <span class="user-name">{{ user?.nickname || user?.name }}</span>
              <span v-if="isAdmin" class="badge-admin">系统管理员</span>
              <ProtoIcon name="chevron" :size="13" />
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人设置</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import ProtoIcon from '@/components/ProtoIcon.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const user = computed(() => store.user)
const isAdmin = computed(() => store.isSystemAdmin())
const currentTitle = computed(() => (route.meta.title as string) || '')

interface MenuItem {
  path: string
  label: string
  icon: string
  admin?: boolean
}

const navWork: MenuItem[] = [
  { path: '/', label: '工作台', icon: 'home' },
  { path: '/applications', label: '应用看板', icon: 'grid' },
  { path: '/modules', label: '模块', icon: 'layers' },
  // Token 是个人资产：AI 访问令牌人人可管，流水线 Token 在页内按角色隐藏
  { path: '/tokens', label: 'Token 管理', icon: 'key' }
]
const navConfig: MenuItem[] = [
  { path: '/templates', label: '模板库', icon: 'book', admin: true },
  { path: '/environments', label: '环境管理', icon: 'sliders', admin: true }
]
const navSystem: MenuItem[] = [
  { path: '/users', label: '用户管理', icon: 'users', admin: true },
  { path: '/audit', label: '审计日志', icon: 'file', admin: true },
  { path: '/backups', label: '备份恢复', icon: 'archive', admin: true },
  { path: '/settings', label: '系统管理', icon: 'sliders', admin: true }
]
const navHelp: MenuItem[] = [
  { path: '/pipeline-docs', label: '流水线接入', icon: 'book' }
]

const menuGroups = computed(() => {
  const visible = (list: MenuItem[]) =>
    list.filter((m) => !m.admin || isAdmin.value)
  return [
    { label: '配置', items: visible(navWork.concat(navConfig)) },
    { label: '系统', items: visible(navSystem) },
    { label: '帮助', items: visible(navHelp) }
  ].filter((g) => g.items.length > 0)
})

function isActive(path: string) {
  if (path === '/') return route.path === '/'
  return route.path.startsWith(path)
}

const health = ref<'ok' | 'down' | 'unknown'>('unknown')
const todoCount = ref(0)
const healthText = computed(() => {
  if (health.value === 'ok') return 'v1.0.0 · PG 正常'
  if (health.value === 'down') return 'v1.0.0 · PG / Redis 异常'
  return 'v1.0.0 · 检测中'
})

async function loadMeta() {
  try {
    const r = await fetch('/api/console/dashboard/todos', { credentials: 'same-origin' })
    if (r.ok) {
      const body = await r.json()
      const d = body?.data || {}
      todoCount.value =
        (d.missingRequired?.length || 0) +
        (d.templateUpdates?.length || 0) +
        (d.expiringTokens?.length || 0)
    }
  } catch {
    // 侧栏角标失败不影响使用
  }
}

async function checkHealth() {
  try {
    const r = await fetch('/actuator/health', { credentials: 'same-origin' })
    const body = await r.json().catch(() => null)
    health.value = r.ok && body?.status === 'UP' ? 'ok' : 'down'
  } catch {
    health.value = 'down'
  }
}

async function onCommand(cmd: string) {
  if (cmd === 'profile') {
    router.push('/profile')
  } else if (cmd === 'logout') {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
    await store.logout()
    router.push('/login')
  }
}

onMounted(() => {
  void checkHealth()
  void loadMeta()
})
</script>

<style scoped>
.layout {
  display: flex;
  height: 100%;
  background: var(--bg);
}

.sidebar {
  width: var(--sb);
  flex: 0 0 var(--sb);
  background: var(--surface);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.brand {
  height: var(--hd);
  flex: 0 0 var(--hd);
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.brand-mark {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border-radius: 7px;
  background: linear-gradient(140deg, #3b82f6, #1d4ed8);
  color: #fff;
  display: grid;
  place-items: center;
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.35);
}

.brand-name {
  font-size: 14.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.brand-sub {
  font-size: 10.5px;
  color: var(--text-3);
  letter-spacing: 0.04em;
  margin-top: -2px;
}

.nav {
  flex: 1;
  overflow-y: auto;
  padding: 6px 10px 16px;
}

.nav-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-3);
  letter-spacing: 0.07em;
  padding: 14px 8px 6px;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 10px;
  margin-bottom: 1px;
  border-radius: var(--r-sm);
  color: var(--text-2);
  font-size: 13.5px;
  white-space: nowrap;
  transition: background 0.14s, color 0.14s;
}

.nav-item:hover {
  background: var(--surface-3);
  color: var(--text);
}

.nav-item.active {
  background: var(--primary-50);
  color: var(--primary-600);
  font-weight: 600;
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 7px;
  bottom: 7px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--primary);
}

.sidebar-foot {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 14px;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
  color: var(--text-3);
  white-space: nowrap;
}

.health-dot {
  width: 6px;
  height: 6px;
  border-radius: 99px;
  flex: 0 0 6px;
  background: #c3c9d4;
}

.health-dot.ok {
  background: var(--ok);
  box-shadow: 0 0 0 3px rgba(4, 120, 87, 0.1);
}

.health-dot.down {
  background: var(--danger);
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.1);
}

.ver {
  margin-left: auto;
  font-family: var(--mono);
  font-size: 10.5px;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: var(--hd);
  flex: 0 0 var(--hd);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-3);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.nav-count {
  margin-left: auto;
  font-size: 11px;
  font-weight: 600;
  background: var(--danger);
  color: #fff;
  border-radius: 9px;
  padding: 0 6px;
  line-height: 16px;
}

.crumb-home {
  cursor: pointer;
}

.crumb-home:hover {
  color: var(--text);
}

.crumb .sep {
  color: #c9cdd6;
}

.crumb b {
  color: var(--text);
  font-weight: 600;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
}

.badge-admin {
  font-size: 10.5px;
  font-weight: 600;
  color: var(--primary-700);
  background: var(--primary-50);
  border: 1px solid var(--primary-100);
  padding: 1px 6px;
  border-radius: 5px;
  letter-spacing: 0.02em;
}

.content {
  flex: 1;
  overflow: auto;
}

@media (max-width: 1023px) {
  .brand-text,
  .nav-text,
  .health-text,
  .ver {
    display: none;
  }

  .brand,
  .nav-item {
    justify-content: center;
    padding: 0 8px;
  }

  .sidebar-foot {
    justify-content: center;
  }

  .topbar {
    padding: 0 16px;
  }
}
</style>
