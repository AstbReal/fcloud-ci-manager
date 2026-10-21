/**
 * 极轻量的只读高亮：不引第三方库，只识别配置文件里最常见的几种记号。
 * 返回 token 数组，由模板渲染成 <span>，全程 textContent，不存在注入面。
 *
 * 定位是「让配置文件更好读」，不是语法高亮：全部逐行判断，不认跨行结构
 * （YAML 缩进、Dockerfile 的续行、shell 的 heredoc 都只能一行一行看）。
 */
export interface Token {
  t: string
  /** key | str | num | bool | com | var | tag */
  c?: string
}

/** 支持高亮的语言；kv 是兜底的键值风格（env/properties/ini/conf/cfg/toml） */
export type Lang = 'yaml' | 'dockerfile' | 'shell' | 'json' | 'kv'

const COMMENT_PREFIX = /^\s*(#|\/\/|;|--)/

const VALUE_TOKEN =
  /('[^']*'|"[^"]*"|\$\{[^}]*\}|\$\([^)]*\)|\$[A-Za-z_][\w]*|`[^`]*`|\b-?\d+(?:\.\d+)?\b|(?<![-\w.])(?:true|false|null|yes|no|on|off)\b)/g

/** `KEY=value` / `key: value` */
const KEY_LINE = /^(\s*)([A-Za-z_][\w.\-]*)(\s*[=:])(.*)$/

/** YAML 列表项里的第一个键：`- name: x` / `  - port: 8080` */
const YAML_ITEM_KEY = /^(\s*-\s+)([A-Za-z_][\w.\-]*)(\s*:)(.*)$/

/** shell 里 `export FOO=bar` / `local x=1` 这类带前缀的赋值 */
const SHELL_ASSIGN = /^(\s*)((?:export|local|readonly|declare|typeset)\s+)([A-Za-z_]\w*)(\s*=)(.*)$/

/** shell 行首关键字 */
const SHELL_KEYWORD =
  /^(\s*)(if|then|else|elif|fi|for|while|until|do|done|case|esac|in|function)\b(.*)$/

/** Dockerfile 指令 */
const DOCKER_INSTRUCTION =
  /^(\s*)(FROM|RUN|CMD|LABEL|MAINTAINER|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|USER|WORKDIR|ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL)\b(.*)$/i

/** Dockerfile 里 ENV/ARG/LABEL 的 `KEY=VALUE` 与 `KEY VALUE` 两种写法 */
const DOCKER_KEY_VALUE = /^([A-Za-z_]\w*)(=)(.*)$/
const DOCKER_KEY_SPACED = /^([A-Za-z_]\w*)(\s+)(.*)$/

/** 含小数点的版本号/地址：10.0.2.9、1.25、v1.2.3 */
const DOTTED = /\b\d+(?:\.\d+)+\b/g

export function langOf(fileKey: string): Lang {
  const name = fileKey.toLowerCase()
  // Dockerfile 没有扩展名：Dockerfile / Dockerfile.prod / prod.dockerfile
  if (/^dockerfile(\..+)?$/.test(name) || /\.dockerfile$/.test(name)) return 'dockerfile'
  if (/\.sh$/.test(name) || /\.(bash|zsh)$/.test(name)) return 'shell'
  if (/\.json$/.test(name)) return 'json'
  if (/\.(ya?ml)$/.test(name)) return 'yaml'
  return 'kv'
}

export function highlightLine(line: string, lang: Lang = 'kv'): Token[] {
  if (!line) return [{ t: '' }]
  if (COMMENT_PREFIX.test(line)) return [{ t: line, c: 'com' }]
  switch (lang) {
    case 'dockerfile':
      return highlightDockerfile(line)
    case 'shell':
      return highlightShell(line)
    case 'yaml':
      return highlightYaml(line)
    default:
      return highlightKv(line)
  }
}

/** JSON 只有引号字符串和数字，交给键值兜底即可 */
function highlightKv(line: string): Token[] {
  const m = line.match(KEY_LINE)
  if (m) {
    const [, indent, key, sep, rest] = m
    return [{ t: indent }, { t: key, c: 'key' }, { t: sep }, ...highlightValue(rest)]
  }
  return highlightValue(line)
}

function highlightYaml(line: string): Token[] {
  const item = line.match(YAML_ITEM_KEY)
  if (item) {
    const [, dash, key, sep, rest] = item
    return [{ t: dash }, { t: key, c: 'key' }, { t: sep }, ...highlightValue(rest)]
  }
  // 块标量（`|` `>` 及其修饰）单独一色，块里的内容仍按普通行处理
  const block = line.match(/^(\s*)([|>][+-]?\d*|\|-|>-)\s*$/)
  if (block) return [{ t: block[1] }, { t: block[2], c: 'tag' }]
  // 锚点/别名
  const anchor = line.match(/^(\s*)([&*][A-Za-z_][\w-]*)(.*)$/)
  if (anchor) {
    const [, indent, ref, rest] = anchor
    return [{ t: indent }, { t: ref, c: 'tag' }, ...highlightValue(rest)]
  }
  return highlightKv(line)
}

function highlightShell(line: string): Token[] {
  const assign = line.match(SHELL_ASSIGN)
  if (assign) {
    const [, indent, prefix, key, eq, rest] = assign
    return [
      { t: indent },
      { t: prefix, c: 'key' },
      { t: key, c: 'key' },
      { t: eq },
      ...highlightValue(rest)
    ]
  }
  const kw = line.match(SHELL_KEYWORD)
  if (kw) {
    const [, indent, word, rest] = kw
    return [{ t: indent }, { t: word, c: 'key' }, ...highlightValue(rest)]
  }
  return highlightKv(line)
}

function highlightDockerfile(line: string): Token[] {
  const m = line.match(DOCKER_INSTRUCTION)
  if (!m) return highlightKv(line)
  const [, indent, word, rest] = m
  const out: Token[] = [{ t: indent }, { t: word, c: 'key' }]
  const upper = word.toUpperCase()
  if (upper === 'ENV' || upper === 'ARG' || upper === 'LABEL') {
    // `ENV K=V K2=V2` / `ENV K V`：逐个键值着色
    out.push(...highlightAssignments(rest))
  } else {
    out.push(...highlightValue(rest))
  }
  return out
}

/** 一行里多个 `K=V` 或 `K V`，按原文本逐段切分 */
function highlightAssignments(text: string): Token[] {
  const out: Token[] = []
  // 指令后的第一个空白段单独保留
  const lead = text.match(/^\s*/)
  if (lead && lead[0]) {
    out.push({ t: lead[0] })
    text = text.slice(lead[0].length)
  }
  while (text) {
    const kv = text.match(DOCKER_KEY_VALUE)
    const spaced = text.match(DOCKER_KEY_SPACED)
    if (kv && (!spaced || kv[0].length >= spaced[0].length)) {
      const [, key, eq, value] = kv
      // `KEY=VALUE` 的等号紧跟键名
      out.push({ t: key, c: 'key' }, { t: eq }, ...highlightValue(value))
      text = text.slice(kv[0].length)
    } else if (spaced) {
      const [, key, gap, value] = spaced
      out.push({ t: key, c: 'key' }, { t: gap }, ...highlightValue(value))
      text = text.slice(spaced[0].length)
    } else {
      out.push(...highlightValue(text))
      break
    }
    const gap = text.match(/^\s+/)
    if (!gap) break
    out.push({ t: gap[0] })
    text = text.slice(gap[0].length)
  }
  return out
}

function highlightValue(text: string): Token[] {
  // 行尾注释单独切出来
  const cut = findInlineComment(text)
  const body = cut >= 0 ? text.slice(0, cut) : text
  const comment = cut >= 0 ? text.slice(cut) : ''

  const out: Token[] = []
  let last = 0
  // 先切带小数点的一整串（10.0.2.9 / 1.25 / v1.2.3），避免被数字规则切碎
  for (const m of body.matchAll(DOTTED)) {
    const idx = m.index ?? 0
    if (idx > last) out.push({ t: body.slice(last, idx) })
    out.push({ t: m[0], c: 'num' })
    last = idx + m[0].length
  }
  if (last < body.length) out.push({ t: body.slice(last) })
  const merged = mergeValueTokens(out)
  if (comment) merged.push({ t: comment, c: 'com' })
  return merged
}

/** 把纯文本段再按 VALUE_TOKEN 细分，纯文本段原样保留 */
function mergeValueTokens(segs: Token[]): Token[] {
  const out: Token[] = []
  for (const seg of segs) {
    if (seg.c) {
      out.push(seg)
      continue
    }
    const body = seg.t
    let last = 0
    for (const m of body.matchAll(VALUE_TOKEN)) {
      const i = m.index ?? 0
      if (i > last) out.push({ t: body.slice(last, i) })
      out.push({ t: m[0], c: classify(m[0]) })
      last = i + m[0].length
    }
    if (last < body.length) out.push({ t: body.slice(last) })
    if (!body) out.push({ t: body })
  }
  return out
}

function classify(v: string): string {
  if (v.startsWith("'") || v.startsWith('"')) return 'str'
  if (v.startsWith('${') || v.startsWith('$(') || v.startsWith('$')) return 'var'
  if (v.startsWith('`')) return 'str'
  if (/^-?\d/.test(v)) return 'num'
  if (/^(true|false|null|yes|no|on|off)$/i.test(v)) return 'bool'
  return 'num'
}

/** 找引号外的第一个行内注释起点（`#` 前需有空白，避免误伤 value 里的 #） */
function findInlineComment(s: string): number {
  let quote: string | null = null
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if (quote) {
      if (ch === quote) quote = null
      continue
    }
    if (ch === "'" || ch === '"') {
      quote = ch
      continue
    }
    if (ch === '#' && (i === 0 || /\s/.test(s[i - 1]))) return i
  }
  return -1
}

/** 根据文件名判断是否值得高亮（纯文本类才高亮） */
export function isHighlightable(fileKey: string): boolean {
  return /\.(ya?ml|json|properties|env|conf|ini|toml|sql|sh|bash|zsh|xml|txt|cfg)$/i.test(fileKey)
    || /^dockerfile(\..+)?$/i.test(fileKey)
    || /\.dockerfile$/i.test(fileKey)
}

/** 能按文本预览的文件名判断（高亮不一定要，但文本预览要） */
export function isTextLike(fileKey: string, mime?: string | null): boolean {
  const byName =
    /\.(ya?ml|json|properties|env|conf|ini|toml|sql|sh|bash|zsh|xml|txt|cfg|md|log)$/i.test(
      fileKey
    ) ||
    /^dockerfile(\..+)?$/i.test(fileKey) ||
    /\.dockerfile$/i.test(fileKey)
  // 上传时浏览器/curl 常给 octet-stream，这种泛型 MIME 不可信，按扩展名判断
  const generic =
    !mime ||
    mime === 'application/octet-stream' ||
    mime === 'binary/octet-stream' ||
    mime === 'application/unknown'
  if (generic) return byName
  if (mime.startsWith('text/')) return true
  if (/(json|yaml|yml|xml|javascript|x-sh|x-yaml)/i.test(mime)) return true
  if (/^(image|audio|video)\//i.test(mime)) return false
  if (/application\/(zip|gzip|x-tar|pdf|zip-compressed)/i.test(mime)) return false
  return byName
}

/** 密钥类文件：默认遮罩，展开需审计 */
export function isSensitive(fileKey: string): boolean {
  return /\.(key|pem|p12|pfx|crt|cer|jks|keystore|env)$/i.test(fileKey)
    || /(secret|password|passwd|private)/i.test(fileKey)
}
