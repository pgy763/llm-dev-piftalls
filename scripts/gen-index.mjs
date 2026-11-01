// 把 docs/ 下 40 篇踩坑笔记扫一遍，生成：
//   1. docs/<分类>/index.md   —— 每类的条目表（关键词 / 一句话原因 / 链接）
//   2. docs/all.md            —— 可筛选的总表页
//   3. docs/public/index.json —— 上面那页用的数据
//
// 只依赖文件名、H1、「## 报错」下的第一行代码、「## 为什么」下的第一句。
// 所以新增一篇笔记不用改这个脚本，也不用改侧边栏。

import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..')
const DOCS = path.join(ROOT, 'docs')

/** 分类顺序 = 侧边栏顺序 = 首页顺序。java 放最前，因为它是当前主攻方向。 */
const CATEGORIES = [
  { dir: 'java', text: 'Java 与框架集成', note: 'Spring AI / LangChain4j / Spring Boot 3，当前主攻' },
  { dir: 'auth', text: '密钥与鉴权', note: 'key、额度、中转站' },
  { dir: 'endpoint', text: '接口与模型', note: 'base_url、模型名、上下文、超时' },
  { dir: 'env', text: '环境与编码', note: 'Windows、GBK、依赖版本' },
  { dir: 'rag', text: 'RAG 与检索', note: '切分、向量维度' },
  { dir: 'agent', text: 'Agent 与工具', note: '工具描述、循环、SQL 校验' },
  { dir: 'python', text: 'Python 与类型', note: '早期 LangChain 阶段，不再新增' }
]

function parseArticle(file, category) {
  const raw = fs.readFileSync(file, 'utf8')
  const lines = raw.split(/\r?\n/)
  const name = path.basename(file, '.md')

  const titleLine = lines.find((l) => l.startsWith('# '))
  const title = (titleLine ?? '# ' + name).slice(2).trim().replace(/`/g, '')

  // 「## 报错」章节：优先取代码块第一行 —— 那才是用户手上那句报错。
  // 没有代码块的（静默错误类），退回该段第一行文字。
  let keyword = ''
  // 老条目用「## 报错」，静默错误类用「## 现象」—— 两个都认
  const errIdx = lines.findIndex((l) => {
    const h = l.trim()
    return h === '## 报错' || h === '## 现象'
  })
  if (errIdx >= 0) {
    const section = []
    for (let i = errIdx + 1; i < lines.length; i++) {
      if (lines[i].trim().startsWith('## ')) break
      section.push(lines[i])
    }
    const fenceAt = section.findIndex((l) => l.trim().startsWith('```'))
    if (fenceAt >= 0) {
      const inner = section.slice(fenceAt + 1)
      const end = inner.findIndex((l) => l.trim().startsWith('```'))
      const block = (end >= 0 ? inner.slice(0, end) : inner).map((l) => l.trim()).filter(Boolean)
      // 跳过 *** 分隔线这类「去掉记号后什么都不剩」的行
      keyword = block.find((l) => l.replace(/[*_\-]/g, '').trim().length > 0) ?? ''
    } else {
      keyword = section.map((l) => l.trim()).find((l) => l && !l.startsWith('![')) ?? ''
    }
  }

  // 「## 为什么」下的第一句 —— 一句话原因
  let summary = ''
  const whyIdx = lines.findIndex((l) => l.trim() === '## 为什么')
  if (whyIdx >= 0) {
    const body = []
    for (let i = whyIdx + 1; i < lines.length; i++) {
      if (lines[i].trim().startsWith('## ')) break
      body.push(lines[i])
    }
    // 去掉 markdown 记号（**粗体**、`代码`、表格竖线），只留可读的纯文本
    const cleaned = body
      .join(' ')
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/\|/g, ' ')
      .replace(/[*`>#]/g, '')
      .replace(/\s+/g, ' ')
      .trim()
    const firstSentence = cleaned.split(/[。！？]/)[0] ?? cleaned
    let s = (firstSentence || cleaned).trim()
    // 截断时优先停在标点处，别把句子切一半
    if (s.length > 52) {
      const cut = Math.max(s.lastIndexOf('，'), s.lastIndexOf('；'), s.lastIndexOf('：'), s.lastIndexOf(','))
      s = (cut > 20 ? s.slice(0, cut) : s.slice(0, 52)).trim() + '…'
    }
    summary = s
  }

  return {
    name,
    title,
    keyword: keyword.replace(/[*]/g, '').slice(0, 80),
    summary,
    category: category.text,
    url: `/${category.dir}/${encodeURI(name)}`,
    file: `./${encodeURI(name)}`
  }
}

function collect() {
  const out = []
  for (const cat of CATEGORIES) {
    const dir = path.join(DOCS, cat.dir)
    if (!fs.existsSync(dir)) continue
    const files = fs
      .readdirSync(dir)
      .filter((f) => f.endsWith('.md') && f !== 'index.md')
      .sort((a, b) => a.localeCompare(b, 'zh'))
    for (const f of files) out.push(parseArticle(path.join(dir, f), cat))
  }
  return out
}

function writeCategoryPages(all) {
  for (const cat of CATEGORIES) {
    const items = all.filter((a) => a.category === cat.text)
    if (!items.length) continue
    const rows = items
      .map((a) => `| ${a.keyword ? '`' + a.keyword.replace(/\|/g, '\\|') + '`' : a.title} | ${a.summary} | [看 →](${a.file}) |`)
      .join('\n')
    const md = `---
title: ${cat.text}
---

# ${cat.text}

${cat.note} —— 共 **${items.length}** 条。

> 不用从头读。把报错里最独特的那个词拿去搜，或者对着下面这张表扫一眼。

| 报错关键词 | 一句话原因 | 文件 |
| --- | --- | --- |
${rows}
`
    fs.writeFileSync(path.join(DOCS, cat.dir, 'index.md'), md)
  }
}

function writeAllPage(all) {
  const md = `---
title: 全部条目
---

# 全部条目（${all.length} 条）

按关键词、症状、分类筛选。输入框支持中文和英文，空格分词。

<PitfallSearch />
`
  fs.writeFileSync(path.join(DOCS, 'all.md'), md)
}

function writeJson(all) {
  const dir = path.join(DOCS, 'public')
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(
    path.join(dir, 'index.json'),
    JSON.stringify(
      all.map((a) => ({ t: a.title, k: a.keyword, s: a.summary, c: a.category, u: a.url })),
      null,
      0
    )
  )
}

const all = collect()
writeCategoryPages(all)
writeAllPage(all)
writeJson(all)

const byCat = CATEGORIES.map((c) => `${c.dir}=${all.filter((a) => a.category === c.text).length}`).join(' ')
console.log(`生成完成：${all.length} 条（${byCat}）`)