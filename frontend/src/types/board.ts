/** 应用看板共享类型（与 /api/console/applications/board 返回结构对应） */
import type { Id } from './id'

export interface ModuleHealth {
  /** 缺必填项的「变量 × 环境」对数 */
  missingRequired: number
  /** 关联模板有未同步变更 */
  templatePending: boolean
}

export interface MiniModule {
  id: Id
  code: string
  name: string
  description?: string
  groupName?: string
  health?: ModuleHealth
}

export interface BoardApp {
  id: Id
  code: string
  name: string
  description?: string
  sortOrder: number
  /** 仓库地址；GitLab 导入时自动写入，手工应用可为空 */
  repoUrl?: string
  /** 来源 GitLab 项目 id；手工创建为空，编辑时只读展示 */
  gitlabProjectId?: Id
  privateModules: MiniModule[]
  subscribedModules: MiniModule[]
}

export interface SharedCard {
  id: Id
  code: string
  name: string
  description?: string
  subscriptionCount: number
}

export interface BoardData {
  applications: BoardApp[]
  sharedModules: SharedCard[]
}
