---
layout: home

hero:
  name: LLM 应用开发踩坑手册
  text: 按报错关键词索引
  tagline: 你在深夜遇到的那些报错，这里可能都记着。Java 生态优先，不按学习顺序，按症状查。
  actions:
    - theme: brand
      text: 全部条目（共 <!--COUNT--> 条）
      link: /all
    - theme: alt
      text: 从哪儿开始
      link: /usage

features:
  - title: Java 优先
    details: 市面上的 AI 踩坑几乎全是 Python 的。这里优先记录 Spring AI / LangChain4j / Spring Boot 3 的问题，Python 的早期条目也留着。
  - title: 按报错索引
    details: 你手上只有报错，没有章节号。所以文件名就是症状，索引表就是搜索入口。
  - title: 四段固定结构
    details: 报错原文 / 为什么 / 怎么改 / 怎么预防。看完知道怎么修，也知道下次怎么躲。
  - title: 标了环境版本
    details: 大部分坑的根因是版本差异。不标版本的坑等于没写，所以每条都带一张环境对照表。
---

## 三条入口

| 你手上的东西 | 从这儿进 |
| --- | --- |
| 一句报错原文 | [全部条目](/all) —— 粘贴关键词筛选，支持中文和英文 |
| 只知道现象（不报错） | [怎么用 · 按症状找](/usage#按症状找) |
| 想知道该不该信这里 | [这个仓库和别处有什么不同](/usage#和别处有什么不同) |

## 分类

| 分类 | 条数 | 说明 |
| --- | --- | --- |
| [Java 与框架集成](/java/) | 16 | Spring AI / LangChain4j / Spring Boot 3，当前主攻 |
| [密钥与鉴权](/auth/) | 5 | key、额度、中转站 |
| [接口与模型](/endpoint/) | 4 | base_url、模型名、上下文、超时 |
| [环境与编码](/env/) | 5 | Windows、GBK、依赖版本 |
| [RAG 与检索](/rag/) | 2 | 切分、向量维度 |
| [Agent 与工具](/agent/) | 3 | 工具描述、循环、SQL 校验 |
| [Python 与类型](/python/) | 5 | 早期 LangChain 阶段，不再新增 |

> 配套项目：[hello-ai-backend](https://github.com/pgy763/hello-ai-backend) —— 写给 Java 后端工程师的 AI 接入手册。这里记「怎么错」，那边讲「怎么对」。