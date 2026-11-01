import { defineConfig } from 'vitepress'
import fs from 'node:fs'
import path from 'node:path'

const DOCS = path.resolve(import.meta.dirname, '..')

const CATEGORIES = [
  { dir: 'java', text: 'Java 与框架集成' },
  { dir: 'auth', text: '密钥与鉴权' },
  { dir: 'endpoint', text: '接口与模型' },
  { dir: 'env', text: '环境与编码' },
  { dir: 'rag', text: 'RAG 与检索' },
  { dir: 'agent', text: 'Agent 与工具' },
  { dir: 'python', text: 'Python 与类型' }
]

/** 侧边栏从文件系统直接生成：加一篇 md 就自动出现，不用改配置。 */
function sidebar() {
  return CATEGORIES.map((cat) => {
    const dir = path.join(DOCS, cat.dir)
    if (!fs.existsSync(dir)) return null
    const items = fs
      .readdirSync(dir)
      .filter((f) => f.endsWith('.md') && f !== 'index.md')
      .sort((a, b) => a.localeCompare(b, 'zh'))
      .map((f) => {
        const raw = fs.readFileSync(path.join(dir, f), 'utf8')
        const h1 = raw.split(/\r?\n/).find((l) => l.startsWith('# ')) ?? '# ' + f
        // 标题很长，侧边栏取前面一段，避免换行
        const title = h1.slice(2).replace(/`/g, '').trim()
        return {
          text: title.length > 34 ? title.slice(0, 34) + '…' : title,
          link: `/${cat.dir}/${path.basename(f, '.md')}`
        }
      })
    return { text: cat.text, collapsed: cat.dir !== 'java', items }
  }).filter(Boolean)
}

export default defineConfig({
  lang: 'zh-CN',
  title: 'LLM 应用开发踩坑手册',
  description: '按报错关键词索引的 LLM 应用开发踩坑记录，Java 生态优先',
  base: '/llm-dev-pitfalls/',
  cleanUrls: true,
  lastUpdated: true,

  head: [
    ['meta', { name: 'theme-color', content: '#3c8772' }],
    ['meta', { property: 'og:type', content: 'website' }]
  ],

  themeConfig: {
    nav: [
      { text: '首页', link: '/' },
      { text: '全部条目', link: '/all' },
      { text: '怎么用', link: '/usage' },
      {
        text: '配套',
        items: [
          { text: 'hello-ai-backend（接入手册）', link: 'https://github.com/pgy763/hello-ai-backend' },
          { text: '提交你的踩坑（Issue）', link: 'https://github.com/pgy763/llm-dev-pitfalls/issues' }
        ]
      }
    ],

    sidebar: sidebar(),

    outline: { level: [2, 3], label: '本页目录' },

    docFooter: { prev: '上一条', next: '下一条' },
    lastUpdatedText: '最后更新',
    returnToTopLabel: '回到顶部',
    darkModeSwitchLabel: '主题',
    sidebarMenuLabel: '目录',

    search: {
      provider: 'local',
      options: {
        detailedView: true,
        translations: {
          button: { buttonText: '搜索报错关键词', buttonAriaLabel: '搜索' },
          modal: {
            noResultsText: '没找到。把报错原文贴到 Issue 里吧。',
            resetButtonTitle: '清除',
            footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' }
          }
        }
      }
    },

    editLink: {
      pattern: 'https://github.com/pgy763/llm-dev-pitfalls/edit/main/docs/:path',
      text: '发现错误？帮我改一版'
    },

    footer: {
      message: '按报错关键词索引，不按学习顺序',
      copyright: 'MIT Licensed'
    }
  }
})