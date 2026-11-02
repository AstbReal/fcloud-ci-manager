/**
 * 复制文本：优先异步剪贴板 API，失败再退回 execCommand。
 *
 * 关键前提：`navigator.clipboard` 只在**安全上下文**（HTTPS 或 localhost）存在。
 * 内网用 http://10.x.x.x:8080 这种地址访问时它是 undefined，直接调
 * `navigator.clipboard.writeText` 会抛 TypeError —— 表现就是"点了复制没反应/提示失败"。
 * 因此非安全上下文直接走 `document.execCommand('copy')`（已废弃但各大浏览器仍实现），
 * 并且必须**同步**调用，避免在 await 之后丢掉用户手势。
 */
export const COPY_FAIL_HINT =
  '复制被浏览器拦截：当前是 HTTP 非安全环境，网页没有剪贴板权限。请手动选中文本复制，或改用 HTTPS 访问管理端'

/** 当前页面能否用 Clipboard API（HTTPS / localhost 才有） */
export function canUseClipboardApi(): boolean {
  return typeof window !== 'undefined' && window.isSecureContext && !!navigator.clipboard?.writeText
}

export async function copyText(text: string): Promise<boolean> {
  if (canUseClipboardApi()) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限被拒 / 文档失焦：继续试 execCommand
    }
  }
  return legacyCopy(text)
}

/** 兜底复制：一次性 textarea + execCommand，同步执行 */
function legacyCopy(text: string): boolean {
  const prevFocus = document.activeElement as HTMLElement | null
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', '')
  // 定位到视口外而不是 display:none：后者选不中，复制出来是空串
  ta.style.position = 'fixed'
  ta.style.top = '0'
  ta.style.left = '-9999px'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.focus()
  ta.select()
  ta.setSelectionRange(0, ta.value.length)

  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(ta)
  // 焦点还给用户刚才点的位置，否则键盘操作会丢
  prevFocus?.focus?.({ preventScroll: true })
  return ok
}

/**
 * 复制失败后的降级：把目标节点整段选中，用户直接 Ctrl+C 即可。
 * 比只说"请手动选择"有用得多——长代码块手拖选很痛苦。
 */
export function selectTextIn(el: Element | null | undefined): boolean {
  if (!el) return false
  const sel = window.getSelection()
  if (!sel) return false
  const range = document.createRange()
  range.selectNodeContents(el)
  sel.removeAllRanges()
  sel.addRange(range)
  return true
}
