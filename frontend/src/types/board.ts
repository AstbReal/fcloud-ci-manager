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
