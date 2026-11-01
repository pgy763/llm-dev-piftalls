---
title: 密钥与鉴权
---

# 密钥与鉴权

key、额度、中转站 —— 共 **7** 条。

> 不用从头读。把报错里最独特的那个词拿去搜，或者对着下面这张表扫一眼。

| 报错关键词 | 一句话原因 | 文件 |
| --- | --- | --- |
| `openai.AuthenticationError: Error code: 401 -` | key、base_url、客户端类、模型名，这四个必须来自同一家 | [看 →](./401%E5%AF%86%E9%92%A5%E4%B8%B2%E5%8F%B0) |
| `openai.AuthenticationError: Error code: 401 -` | 两种情况的 HTTP 状态码几乎一样，含义完全不同： code 含义 该谁处理 --- --- --- invalid_api_key key 不对、被删、环境变量没读到 开发 insufficient_quota 余额/额度用尽、账单未付 运营/财务 billing_not_active 账户未激活或欠费 运营 而 429 又是第三种：请求太快，等一会儿就好… | [看 →](./401%E6%9C%89%E4%B8%A4%E7%A7%8Dkey%E9%94%99%E5%92%8C%E9%92%B1%E6%B2%A1%E4%BA%86) |
| `openai.RateLimitError: Error code: 429 - {'error':` | 模型厂商对 API Key 有速率限制，通常两个维度同时限制： 维度 含义 典型值 --- --- --- RPM 每分钟请求数 60 / 500 / 3000 TPM 每分钟 token 数 100000 / 1000000 你的循环以最大速度发请求… | [看 →](./429%E9%99%90%E6%B5%81) |
| `openai.OpenAIError: The api_key client option must be set either by passing` | os.getenv("DASHSCOPE_API_KEY") 返回了 None，于是等于没传 | [看 →](./%E6%B2%A1%E8%AF%BB%E5%88%B0api-key) |
| `$ git log --all -S "sk-" --oneline` | git commit 记录的是快照，不是差分 | [看 →](./%E5%AF%86%E9%92%A5%E6%8F%90%E4%BA%A4%E8%BF%9B%E4%BA%86git%E5%8E%86%E5%8F%B2) |
| `Unexpected token '<', "<!DOCTYPE "... is not valid JSON` | 中转站（第三方 API 代理）在几种情况下会把请求导向 HTML 页面，而不是 JSON 接口： 情况 返回的内容 --- --- key 不在该分组下 站点的登录/充值页 base_url 少了 /v1 站点首页（200） 站点在维护 公告页 域名被 CDN 接管 云厂商的错误页 浏览器访问能打开页面，于是你以为「服务是通的」—— 但它通的是网页… | [看 →](./%E4%B8%AD%E8%BD%AC%E7%AB%99%E8%BF%94%E5%9B%9E%E7%99%BB%E5%BD%95%E9%A1%B5) |
| `openai.APIStatusError: Error code: 503 -` | 「渠道」是中转站的术语，指的是它上游对接的真实模型供应方 | [看 →](./%E4%B8%AD%E8%BD%AC%E7%AB%99%E6%B2%A1%E6%9C%89%E5%8F%AF%E7%94%A8%E6%B8%A0%E9%81%93) |
