import { nextTick, onBeforeUnmount, ref } from 'vue'
import type { Id } from '@/types/id'

export interface DeepLinkTarget {
  rowId: Id
  envId: Id | null
}

export interface DeepLinkInfo {
  varKey: string
  envCode?: string
  rowId: Id
  envId: Id | null
}

/**
 * §10.5 深链：`/modules/{id}?env=prod&var=DB_HOST`
 *
 * - 链接优先级高于用户上次的筛选状态：先清筛选，再定位
 * - 行加浅蓝底、目标单元格加主色环，4s 后自动淡出
 * - 解析逻辑由调用方提供（前端不重算变量优先级）
 */
export function useDeepLink(
  resolve: (varKey: string, envCode?: string) => DeepLinkTarget | null,
  clearFilters: () => void
) {
  const info = ref<DeepLinkInfo | null>(null)
  const hitRowId = ref<Id | null>(null)
  const hitEnvId = ref<Id | null>(null)
  let timer: number | undefined

  async function apply(varKey?: string | null, envCode?: string | null) {
    if (!varKey) return false
    clearFilters()
    await nextTick()
    const target = resolve(varKey, envCode || undefined)
    if (!target) return false

    info.value = {
      varKey,
      envCode: envCode || undefined,
      rowId: target.rowId,
      envId: target.envId
    }
    hitRowId.value = target.rowId
    hitEnvId.value = target.envId

    await nextTick()
    const el = document.querySelector<HTMLElement>(
      `[data-row-id="${target.rowId}"]`
    )
    el?.scrollIntoView({ block: 'center', behavior: 'smooth' })

    window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      hitRowId.value = null
      hitEnvId.value = null
    }, 4000)
    return true
  }

  function clear() {
    window.clearTimeout(timer)
    info.value = null
    hitRowId.value = null
    hitEnvId.value = null
  }

  onBeforeUnmount(clear)

  return { info, hitRowId, hitEnvId, apply, clear }
}
