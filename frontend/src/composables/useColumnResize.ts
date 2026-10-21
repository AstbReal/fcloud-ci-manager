import { onBeforeUnmount, ref } from 'vue'

export interface ResizableColumn {
  key: string
  label: string
  /** 默认宽度（px）；null 表示「自动吃掉剩余空间」——只有一列可以这样 */
  width: number | null
  /** 拖拽下限（px），默认 72 */
  min?: number
}

const HARD_MAX = 2000
const DEFAULT_MIN = 72

/**
 * 表格列宽拖拽 + 本地记忆。
 *
 * - 宽度只写进调用方的 <colgroup>，不改表格结构。
 * - `width` 为 null 的列是「自适应列」：表宽大于各列之和时由它吃掉差额，
 *   所以默认状态下不会出现横向滚动条；用户主动拖它之后才变成固定宽度，
 *   双击分隔条可以退回自适应。
 * - 宽度存 localStorage（key 由调用方给），刷新后保持。
 *
 * `start()` 的第三个参数是当前「实际渲染宽度」，不是 widths 里的值 ——
 * 自适应列实际宽度通常大于设定值，从实际值起算才不会跳一下。
 */
export function useColumnResize(storageKey: string, columns: readonly ResizableColumn[]) {
  const defaults: Record<string, number | null> = {}
  for (const c of columns) defaults[c.key] = c.width

  const widths = ref<Record<string, number | null>>({ ...defaults })
  const customized = ref(false)

  function colOf(key: string) {
    return columns.find((c) => c.key === key)
  }

  function limit(key: string, w: number): number {
    const min = colOf(key)?.min ?? DEFAULT_MIN
    return Math.max(min, Math.min(HARD_MAX, Math.round(w)))
  }

  function syncCustomized() {
    customized.value = columns.some((c) => widths.value[c.key] !== defaults[c.key])
  }

  function persist() {
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(widths.value))
    } catch {
      /* 隐私模式 / 配额满：列宽不值得为它报错 */
    }
  }

  // 恢复上次列宽
  try {
    const raw = window.localStorage.getItem(storageKey)
    if (raw) {
      const saved = JSON.parse(raw) as Record<string, unknown>
      for (const c of columns) {
        if (!(c.key in saved)) continue
        const w = saved[c.key]
        if (w === null) widths.value[c.key] = null
        else if (typeof w === 'number' && Number.isFinite(w)) widths.value[c.key] = limit(c.key, w)
      }
      syncCustomized()
    }
  } catch {
    /* 存坏了就按默认值来 */
  }

  let drag: { key: string; startX: number; startW: number } | null = null

  function onMove(ev: PointerEvent) {
    if (!drag) return
    widths.value[drag.key] = limit(drag.key, drag.startW + ev.clientX - drag.startX)
  }

  function stop() {
    if (!drag) return
    drag = null
    document.body.classList.remove('col-resizing')
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
    syncCustomized()
    persist()
  }

  function start(key: string, ev: PointerEvent, current: number) {
    if (ev.button !== 0) return
    ev.preventDefault()
    ev.stopPropagation()
    drag = { key, startX: ev.clientX, startW: current }
    document.body.classList.add('col-resizing')
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', stop)
    window.addEventListener('pointercancel', stop)
  }

  /** 键盘：← → 每次 16px，Home 恢复该列默认（自适应列即退回自适应） */
  function step(key: string, delta: number, current: number) {
    widths.value[key] = limit(key, current + delta)
    syncCustomized()
    persist()
  }

  function reset(key: string) {
    widths.value[key] = defaults[key]
    syncCustomized()
    persist()
  }

  function resetAll() {
    widths.value = { ...defaults }
    customized.value = false
    try {
      window.localStorage.removeItem(storageKey)
    } catch {
      /* 同上 */
    }
  }

  onBeforeUnmount(() => {
    stop()
  })

  return { widths, customized, start, step, reset, resetAll }
}
