/** 模块详情 / 变量矩阵共享类型 */
import type { Id } from './id'

export interface Env {
  id: Id
  code: string
  name: string
  color?: string
  sortOrder?: number
  isDefault?: boolean
  parentEnvId?: Id | null
}

export interface MatrixCell {
  value?: string | null
  masked: boolean
  found: boolean
  override: boolean
  inherited: boolean
  source?: string
}

export interface MatrixRow {
  id: Id
  varKey: string
  valueType: 'PLAIN' | 'ENCRYPTED'
  remark?: string
  isRequired: boolean
  sortOrder: number
  /** MODULE = 模块自有 / TEMPLATE = 模板下发 */
  origin?: 'MODULE' | 'TEMPLATE'
  createdBy?: Id
  canReveal: boolean
  cells: Record<string, MatrixCell>
}

export interface MatrixData {
  environments: Env[]
  rows: MatrixRow[]
}

export interface ChainStop {
  environmentId: Id | null
  environmentCode?: string | null
  environmentName: string
  defined: boolean
  masked: boolean
  value?: string | null
  source?: string | null
}

export interface ChainData {
  varId: Id
  varKey: string
  environmentId: Id
  levels: ChainStop[]
}
