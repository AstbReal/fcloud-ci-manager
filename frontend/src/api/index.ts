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

export interface TemplateFileItem {
  id: Id
  templateId: Id
  fileKey: string
  remark?: string | null
  originalName?: string
  mimeType?: string | null
  size: number
  sha256?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface TemplateItem {
  id: Id
  code: string
  name: string
  description?: string
  groupName?: string | null
  varCount?: number
  fileCount?: number
  pendingModules?: number
  createdAt?: string
  updatedAt?: string
}

export interface TemplateFileStatus {
  fileKey: string
  remark?: string | null
  status: 'ADD' | 'UPDATE' | 'CONFLICT'
  missingEnvironments: number
  outdatedEnvironments: number
  conflictEnvironments: number
}

export interface TemplateVarConflict {
  varKey: string
  module: { valueType: string; remark?: string | null; isRequired: boolean }
  template: { valueType: string; remark?: string | null; isRequired: boolean }
}

export interface TemplateDiff {
  added: TemplateVar[]
  changed: Array<{
    varKey: string
    template: { valueType: string; remark?: string | null; isRequired: boolean }
    module: { valueType: string; remark?: string | null; isRequired: boolean }
  }>
  removed: Array<{ id: Id; varKey: string }>
  conflicts: TemplateVarConflict[]
  files: TemplateFileStatus[]
  removedFiles: Array<{ fileKey: string; environmentCount: number }>
  appConflicts: Array<{
    kind: 'VAR' | 'FILE'
    key: string
    moduleCode: string
    shared: boolean
  }>
  hasUpdate: boolean
}

export interface TemplateBindResult {
  addedVars: number
  changedVars: number
  varConflicts: number
  addedFiles: number
  updatedFiles: number
  fileConflicts: number
  environments: number
}

export interface TemplateBatchBindItem {
  moduleId: Id
  applicationId: Id
  applicationCode?: string
  moduleCode: string
  status: 'READY' | 'BOUND' | 'ALREADY' | 'OTHER_TEMPLATE'
  addedVars: number
  changedVars: number
  varConflicts: number
  addedFiles: number
  updatedFiles: number
  fileConflicts: number
  appConflicts: number
  /** 模块已绑定的环境数：绑定模板不改环境 */
  envCount: number
}

/** 模板绑定关系：哪个应用的哪个模块绑了此模板 */
export interface TemplateBindingItem {
  moduleId: Id
  applicationId: Id
  applicationCode?: string | null
  applicationName?: string | null
  moduleCode: string
  syncedAt?: string | null
  pending: boolean
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
    api.get<TemplateItem & { vars: TemplateVar[]; files: TemplateFileItem[] }>(
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
    api.post(`/api/console/admin/templates/${id}/instantiate`, b),
  uploadFile: (id: Id, form: FormData) =>
    api.upload<TemplateFileItem>(`/api/console/admin/templates/${id}/files`, form),
  deleteFile: (id: Id, fileId: Id) =>
    api.del(`/api/console/admin/templates/${id}/files/${fileId}`),
  previewBind: (moduleId: Id, templateId: Id) =>
    api.get<TemplateDiff>(
      `/api/console/admin/modules/${moduleId}/template-preview`,
      { templateId }
    ),
  bind: (moduleId: Id, templateId: Id) =>
    api.post<TemplateBindResult>(`/api/console/admin/modules/${moduleId}/template-bind`, {
      templateId
    }),
  unbind: (moduleId: Id, deleteContent = false) =>
    api.post<{ variables: number; files: number; deleted: boolean }>(
      `/api/console/admin/modules/${moduleId}/template-unbind`,
      { deleteContent }
    ),
  previewBindBatch: (id: Id, b: { moduleCode: string; applicationIds?: Id[] }) =>
    api.post<TemplateBatchBindItem[]>(
      `/api/console/admin/templates/${id}/bind-preview`,
      b
    ),
  bindBatch: (id: Id, b: { moduleCode: string; applicationIds?: Id[] }) =>
    api.post<TemplateBatchBindItem[]>(
      `/api/console/admin/templates/${id}/bind-batch`,
      b
    ),
  bindings: (id: Id) =>
    api.get<TemplateBindingItem[]>(`/api/console/admin/templates/${id}/bindings`),
  diff: (moduleId: Id) =>
    api.get<TemplateDiff>(`/api/console/admin/modules/${moduleId}/template-diff`),
  sync: (
    moduleId: Id,
    b: {
      acceptAdded: string[]
      acceptChanged: string[]
      removedKeys: string[]
      removedAction: 'DELETE' | 'KEEP'
      removedFileKeys: string[]
    }
  ) =>
    api.post(`/api/console/admin/modules/${moduleId}/template-sync`, b)
}

export const userApi = {
  list: (keyword?: string) =>
    api.get<UserItem[]>('/api/console/admin/users', keyword ? { keyword } : undefined),
  setRole: (id: Id, role: string) =>
    api.put<UserItem>(`/api/console/admin/users/${id}/role`, { role })
}
