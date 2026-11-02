import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      {
        path: '',
        name: 'dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '工作台' }
      },
      {
        path: 'applications',
        name: 'applications',
        component: () => import('@/views/ApplicationBoard.vue'),
        meta: { title: '应用看板' }
      },
      {
        path: 'modules',
        name: 'modules',
        component: () => import('@/views/Modules.vue'),
        meta: { title: '模块' }
      },
      {
        path: 'modules/:id',
        name: 'module-detail',
        component: () => import('@/views/ModuleDetail.vue'),
        meta: { title: '模块详情' }
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/SystemSettings.vue'),
        meta: { title: '系统管理', adminOnly: true }
      },
      {
        path: 'templates',
        name: 'templates',
        component: () => import('@/views/Templates.vue'),
        meta: { title: '模板库', adminOnly: true }
      },
      {
        path: 'environments',
        name: 'environments',
        component: () => import('@/views/Environments.vue'),
        meta: { title: '环境管理', adminOnly: true }
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/Users.vue'),
        meta: { title: '用户管理', adminOnly: true }
      },
      {
        path: 'tokens',
        name: 'tokens',
        component: () => import('@/views/Tokens.vue'),
        // 普通用户也能进：AI 访问令牌是「个人」的，不要求管理员；
        // 页内的「流水线 Token」Tab 仍按角色隐藏。
        meta: { title: 'Token 管理' }
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/AuditLogs.vue'),
        meta: { title: '审计日志', adminOnly: true }
      },
      {
        path: 'backups',
        name: 'backups',
        component: () => import('@/views/Backups.vue'),
        meta: { title: '备份恢复', adminOnly: true }
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/Profile.vue'),
        meta: { title: '个人设置' }
      },
      {
        path: 'pipeline-docs',
        name: 'pipeline-docs',
        component: () => import('@/views/PipelineDocs.vue'),
        meta: { title: '流水线接入' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const store = useUserStore()
  const me = await store.loadMe()
  if (!me) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.adminOnly && !store.isSystemAdmin()) {
    return { name: 'dashboard' }
  }
  return true
})

router.afterEach((to) => {
  const t = to.meta.title as string | undefined
  document.title = t ? `${t} · fcloud-ci-manager` : 'fcloud-ci-manager'
})

export default router
