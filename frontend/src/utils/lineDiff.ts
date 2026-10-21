/**
 * 行级 diff：先剪掉公共前后缀，再对中间段做 LCS。
 * 配置文件通常只有几行变化，剪枝后中间段很小，O(n·m) 完全够用。
 */
export interface DiffRow {
  type: 'same' | 'add' | 'del'
  text: string
  /** 旧文件中的行号（add 行为空） */
  left?: number
  /** 新文件中的行号（del 行为空） */
  right?: number
}

const MAX_LINES = 2000

export function diffLines(oldText: string, newText: string): {
  rows: DiffRow[]
  added: number
  removed: number
  truncated: boolean
} {
  const a = oldText.split('\n')
  const b = newText.split('\n')
  const truncated = a.length > MAX_LINES || b.length > MAX_LINES
  const aa = a.slice(0, MAX_LINES)
  const bb = b.slice(0, MAX_LINES)

  // 公共前缀 / 后缀
  let start = 0
  while (start < aa.length && start < bb.length && aa[start] === bb[start]) start++
  let endA = aa.length - 1
  let endB = bb.length - 1
  while (endA >= start && endB >= start && aa[endA] === bb[endB]) {
    endA--
    endB--
  }

  const rows: DiffRow[] = []
  let added = 0
  let removed = 0

  for (let i = 0; i < start; i++) {
    rows.push({ type: 'same', text: aa[i], left: i + 1, right: i + 1 })
  }

  const midA = aa.slice(start, endA + 1)
  const midB = bb.slice(start, endB + 1)
  const ops = lcsOps(midA, midB)
  // midA/midB 是 0 基切片，行号要用绝对位置；取值必须减去 start 偏移
  let la = start
  let lb = start
  for (const op of ops) {
    if (op === 'same') {
      rows.push({
        type: 'same',
        text: midA[la - start],
        left: la + 1,
        right: lb + 1
      })
      la++
      lb++
    } else if (op === 'del') {
      rows.push({ type: 'del', text: midA[la - start], left: la + 1 })
      la++
      removed++
    } else {
      rows.push({ type: 'add', text: midB[lb - start], right: lb + 1 })
      lb++
      added++
    }
  }

  // 公共后缀
  let la2 = endA + 1
  let lb2 = endB + 1
  while (la2 < aa.length) {
    rows.push({
      type: 'same',
      text: aa[la2],
      left: la2 + 1,
      right: lb2 + 1
    })
    la2++
    lb2++
  }

  return { rows, added, removed, truncated }
}

/** 返回操作序列：same / del / add */
function lcsOps(a: string[], b: string[]): Array<'same' | 'del' | 'add'> {
  const n = a.length
  const m = b.length
  if (!n) return b.map(() => 'add' as const)
  if (!m) return a.map(() => 'del' as const)

  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }

  const ops: Array<'same' | 'del' | 'add'> = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      ops.push('same')
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      ops.push('del')
      i++
    } else {
      ops.push('add')
      j++
    }
  }
  while (i < n) {
    ops.push('del')
    i++
  }
  while (j < m) {
    ops.push('add')
    j++
  }
  return ops
}

/** 把连续未变化的行折叠成段，便于渲染「⋯ n 行未变化」 */
export interface DiffBlock {
  kind: 'same' | 'add' | 'del' | 'fold'
  rows: DiffRow[]
}

export function toBlocks(rows: DiffRow[], context = 2): DiffBlock[] {
  const out: DiffBlock[] = []
  let i = 0
  while (i < rows.length) {
    if (rows[i].type !== 'same') {
      const group: DiffRow[] = []
      while (i < rows.length && rows[i].type !== 'same') group.push(rows[i++])
      out.push({ kind: group[0].type as 'add' | 'del', rows: group })
      continue
    }
    const group: DiffRow[] = []
    while (i < rows.length && rows[i].type === 'same') group.push(rows[i++])
    if (group.length <= context * 2 + 1) {
      out.push({ kind: 'same', rows: group })
    } else {
      out.push({ kind: 'same', rows: group.slice(0, context) })
      out.push({ kind: 'fold', rows: group.slice(context, group.length - context) })
      out.push({ kind: 'same', rows: group.slice(group.length - context) })
    }
  }
  return out
}
