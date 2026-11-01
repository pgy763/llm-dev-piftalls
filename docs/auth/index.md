---
title: 密钥与鉴权
---

# 密钥与鉴权

key、额度、中转站 —— 共 **5** 条。

> 不用从头读。把报错里最独特的那个词拿去搜，或者对着下面这张表扫一眼。

| 报错关键词 | 一句话原因 | 文件 |
| --- | --- | --- |
| `openai.AuthenticationError: Error code: 401 -` | key、base_url、客户端类、模型名，这四个必须来自同一家 | [看 →](./401%E5%AF%86%E9%92%A5%E4%B8%B2%E5%8F%B0) |
| `openai.RateLimitError: Error code: 429 - {'error':` | 模型厂商对 API Key 有速率限制，通常两个维度同时限制： 维度 含义 典型值 --- --- --- RPM 每分钟请求数 60 / 500 / 3000 TPM 每分钟 token 数 100000 / 1000000 你的循环以最大速度发请求… | [看 →](./429%E9%99%90%E6%B5%81) |
| `openai.OpenAIError: The api_key client option must be set either by passing` | os.getenv("DASHSCOPE_API_KEY") 返回了 None，于是等于没传 | [看 →](./%E6%B2%A1%E8%AF%BB%E5%88%B0api-key) |
| `$ git log --all -S "sk-" --oneline` | git commit 记录的是快照，不是差分 | [看 →](./%E5%AF%86%E9%92%A5%E6%8F%90%E4%BA%A4%E8%BF%9B%E4%BA%86git%E5%8E%86%E5%8F%B2) |
| `openai.APIStatusError: Error code: 503 -` | 「渠道」是中转站的术语，指的是它上游对接的真实模型供应方 | [看 →](./%E4%B8%AD%E8%BD%AC%E7%AB%99%E6%B2%A1%E6%9C%89%E5%8F%AF%E7%94%A8%E6%B8%A0%E9%81%93) |
