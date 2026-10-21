import { api } from './http'
import type { Id } from '@/types/id'

export interface Env {
  id: Id
  code: string
  name: string
  color?: string
  sortOrder: number
  isDefault: boolean
  parentEnvId?: Id | null
  moduleCount?: number
}

export interface TemplateVar {
  id?: Id
  varKey: string
  valueType: 'PLAIN' | 'ENCRYPTED'
  remark?: string
  defaultValue?: string | null
  isRequired: boolean
  sortOrder: number
}

export interface TemplateItem {
  id: Id
  code: string
  name: string
  description?: string
  groupName?: string | null
  varCount?: number
  pendingModules?: number
  createdAt?: string
  updatedAt?: string
}

export interface UserItem {
  id: Id
  name: string
  nickname?: string
  email: string
  avatarUrl?: string
  globalRole: 'SYSTEM_ADMIN' | 'NONE'
  gitlabId?: string | null
  lastLoginAt?: string | null
  memberCount?: number
}

export const envApi = {
  list: () => api.get<Env[]>('/api/console/admin/environments'),
  create: (b: Partial<Env>) => api.post<Env>('/api/console/admin/environments', b),
  update: (id: Id, b: Partial<Env>) =>
    api.put<Env>(`/api/console/admin/environments/${id}`, b),
  remove: (id: Id) => api.del(`/api/console/admin/environments/${id}`),
  sort: (ids: Id[]) => api.put('/api/console/admin/environments/sort', { ids })
}

export const templateApi = {
  list: () => api.get<TemplateItem[]>('/api/console/admin/templates'),
  detail: (id: Id) =>
    api.get<TemplateItem & { vars: TemplateVar[] }>(
      `/api/console/admin/templates/${id}`
    ),
  create: (b: { code: string; name: string; description?: string; vars: TemplateVar[] }) =>
    api.post('/api/console/admin/templates', b),
  update: (
    id: Id,
    b: { code: string; name: string; description?: string; vars: TemplateVar[] }
  ) => api.put(`/api/console/admin/templates/${id}`, b),
  remove: (id: Id) => api.del(`/api/console/admin/templates/${id}`),
  renameGroup: (from: string, to: string | null) =>
    api.put('/api/console/admin/templates/group', { from, to }),
  saveAs: (moduleId: Id, b: { code: string; name: string; groupName?: string }) =>
    api.post(`/api/console/admin/modules/${moduleId}/save-as-template`, b),
  instantiate: (
    id: Id,
    b: {
      applicationId: Id
      code: string
      name: string
      groupName?: string
      environmentIds?: Id[]
    }
  ) =>
    api.post(`/api/console/admin/templates/${id}/instantiate`, b)
}

export const userApi = {
  list: (keyword?: string) =>
    api.get<UserItem[]>('/api/console/admin/users', keyword ? { keyword } : undefined),
  setRole: (id: Id, role: string) =>
    api.put<UserItem>(`/api/console/admin/users/${id}/role`, { role })
}
