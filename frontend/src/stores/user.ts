import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/http'
import type { Id } from '@/types/id'

export interface UserInfo {
  id: Id
  name: string
  nickname: string
  email: string
  avatarUrl?: string
  globalRole: 'SYSTEM_ADMIN' | 'NONE'
}

export const useUserStore = defineStore('user', () => {
  const user = ref<UserInfo | null>(null)
  const loaded = ref(false)

  async function loadMe(force = false) {
    if (loaded.value && !force) return user.value
    try {
      user.value = await api.get<UserInfo>('/api/console/auth/me')
    } catch {
      user.value = null
    }
    loaded.value = true
    return user.value
  }

  function isSystemAdmin() {
    return user.value?.globalRole === 'SYSTEM_ADMIN'
  }

  async function updateNickname(nickname: string) {
    user.value = await api.put<UserInfo>('/api/console/auth/me', { nickname })
    return user.value
  }

  async function logout() {
    try {
      await api.post('/api/console/auth/logout')
    } finally {
      user.value = null
      loaded.value = false
    }
  }

  return { user, loaded, loadMe, isSystemAdmin, updateNickname, logout }
})
