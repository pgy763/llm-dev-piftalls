---
title: 接口与模型
---

# 接口与模型

base_url、模型名、上下文、超时 —— 共 **4** 条。

> 不用从头读。把报错里最独特的那个词拿去搜，或者对着下面这张表扫一眼。

| 报错关键词 | 一句话原因 | 文件 |
| --- | --- | --- |
| `openai.NotFoundError: Error code: 404 -` | 文档上的模型名通常是「别名」，而别名会随版本更新变化 | [看 →](./%E6%A8%A1%E5%9E%8B%E5%90%8D%E4%B8%8D%E5%AD%98%E5%9C%A8) |
| `$ time python src/agent/text_to_sql_agent.py` | OpenAI SDK 的默认超时是 600 秒（10 分钟） | [看 →](./%E8%AF%B7%E6%B1%82%E6%8C%82%E6%AD%BB%E8%B6%85%E6%97%B6%E5%A4%AA%E9%95%BF) |
| `openai.BadRequestError: Error code: 400 - {'error':` | 每个模型都有一个上下文窗口上限，指的是「输入 + 输出」加起来的 token 总数： 模型 常见窗口 --- --- gpt-4o-mini 128K qwen-plus 128K 部分开源模型本地部署 8K / 16K / 32K 超了就直接 400，不是截断… | [看 →](./%E4%B8%8A%E4%B8%8B%E6%96%87%E8%B6%85%E9%95%BF) |
| `openai.NotFoundError: Error code: 404 -` | 因为你多半把 base_url 写成了完整的请求地址： base_url 的语义是「基础地址」… | [看 →](./base-url%E5%A4%9A%E5%86%99%E4%BA%86%E8%B7%AF%E5%BE%84) |
