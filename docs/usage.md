# 怎么用

## 三步

1. **找最独特的那个词**，不要搜 `error` —— 每条都有。
   - Java 的坑按「错在哪一层」分五类：依赖 / 编译、配置 / 装配、协议 / 序列化、流式 / 并发、部署 / 网关
   - Python 的坑搜 `PydanticUserError`、`context_length_exceeded`、`0x80`
2. 打开对应条目，看「为什么」和「怎么改」
3. 搜不到？[开个 Issue](https://github.com/pgy763/llm-dev-pitfalls/issues) 把报错贴上来，我来补

## 按症状找

不知道报错叫什么，只知道现象？从这儿进：

| 现象 | 大概率是 |
| --- | --- |
| 报错里出现 `class file version` | JDK 版本不一致 → [JDK版本不匹配](/java/JDK版本不匹配) |
| 报错里出现 `No converter for` / `FluxJust` | MVC 里返回了 `Flux` → [MVC项目返回Flux报错](/java/MVC项目返回Flux报错) |
| 报错里出现 `NoSuchMethodError` | 依赖版本冲突 → [LangChain4j模块版本不对齐](/java/LangChain4j模块版本不对齐) |
| 报错里出现 `LinkageError` / `loader constraint violation` | classpath 上有两份同一个库 → [classpath上两份同一个库](/java/classpath上两份同一个库) |
| 报错里出现 `Unrecognized field` | 厂商字段差异 → [Jackson反序列化未知字段](/java/Jackson反序列化未知字段) |
| 报错里出现 `Java 8 date/time type` / `jsr310` | 你用的不是 Spring 那个 `ObjectMapper` → [自定义ObjectMapper不认时间类型](/java/自定义ObjectMapper不认时间类型) |
| `LocalDateTime` 被序列化成数组 `[2026,9,30,...]` | `WRITE_DATES_AS_TIMESTAMPS` 没关 → [自定义ObjectMapper不认时间类型](/java/自定义ObjectMapper不认时间类型) |
| 回答说到一半就断、还不报错 | 流式总超时 → [流式请求设了总超时](/java/流式请求设了总超时) |
| 接口集体变慢、队列一直涨、CPU 却不高（不报错） | 并发调模型把线程池打满 → [并发调用模型把线程池打满](/java/并发调用模型把线程池打满) |
| 报错里出现 `RejectedExecutionException` / `TaskRejectedException` | 线程池拒绝任务，等待队列也满了 → [并发调用模型把线程池打满](/java/并发调用模型把线程池打满) |
| 本地直连逐字出、挂上 Nginx 后整段一次性到达（不报错） | 反代在缓冲流式响应 → [Nginx缓冲导致流式失效](/java/Nginx缓冲导致流式失效) |
| 报错里出现 `upstream timed out` / `ERR_INCOMPLETE_CHUNKED_ENCODING` | 网关读超时或缓冲 → [Nginx缓冲导致流式失效](/java/Nginx缓冲导致流式失效) |
| 上传文件时返回一段 Nginx 的 HTML 错误页，应用日志里搜不到这条请求 | 请求体在代理层就被拒了 → [上传文档报413](/java/上传文档报413) |
| 上传直接 500，报错里出现 `MaxUploadSizeExceededException` | 撞的是 Spring 那层 multipart 上限 → [上传文档报413](/java/上传文档报413) |
| 日志出现 `multiple SLF4J bindings`、`logging.level` 失效（不报错） | classpath 上有第二个日志实现 → [多个SLF4J绑定](/java/多个SLF4J绑定) |
| 报错里出现 `javax/servlet` | Boot 3 下引了 Boot 2 时代的 starter → [SpringBoot3的javax与jakarta](/java/SpringBoot3的javax与jakarta) |
| 启动失败，`NoSuchBeanDefinitionException` 里带 `ChatClient` | Spring AI 只给了 `Builder`，没给单例 → [注入ChatClient找不到Bean](/java/注入ChatClient找不到Bean) |
| 启动报 `NoUniqueBeanDefinitionException`，`found 2: ...ChatClientBuilder` | 两个模型 starter 各注册了一个 Builder → [注入ChatClient找不到Bean](/java/注入ChatClient找不到Bean) |
| 回答里出现其他用户问过的内容（不报错） | 单例 `Assistant` 共用一份 `ChatMemory` → [多用户共用一份ChatMemory](/java/多用户共用一份ChatMemory) |
| 对话越长 `prompt_tokens` 涨得越快、账单超预期 | 历史按「条数」而不是 token 限额 → [多用户共用一份ChatMemory](/java/多用户共用一份ChatMemory) |
| 多实例部署后模型「突然忘了刚才说什么」（不报错） | `ChatMemory` 在进程内存里，请求落到另一台 → [多用户共用一份ChatMemory](/java/多用户共用一份ChatMemory) |
| SSE 连上后固定 30 秒断开，前端自动重连 | `SseEmitter` 用了容器默认超时 → [SSE连接30秒断开](/java/SSE连接30秒断开) |
| 报错里出现 `AsyncRequestTimeoutException` | 连接已被超时关掉，还在往里写 → [SSE连接30秒断开](/java/SSE连接30秒断开) |
| 报错里出现 `block()/blockFirst()/blockLast() are blocking` | 在 Reactor 非阻塞线程上调了 `block()` → [WebFlux里block流式结果](/java/WebFlux里block流式结果) |
| WebFlux 服务并发一高整体卡住、健康检查也超时 | IO 线程里做了阻塞调用 → [WebFlux里block流式结果](/java/WebFlux里block流式结果) |
| 报错里出现 `401` / `403` | 密钥或鉴权 → [密钥与鉴权](/auth/) |
| 报错里出现 `404` | `base_url` 或模型名 → [接口与模型](/endpoint/) |
| 报错里出现 `429` | 限流 → [429限流](/auth/429限流) |
| 报错里出现 `503` | 上游或中转站的问题 → [中转站没有可用渠道](/auth/中转站没有可用渠道) |
| 报错里出现 `gbk` | Windows 编码 → [环境与编码](/env/) |
| 报错里出现 `pydantic` | v1/v2 版本差异 → [Python 与类型](/python/) |
| 程序卡住不动、不报错 | 超时设置 → [请求挂死超时太长](/endpoint/请求挂死超时太长) |
| 不报错，但答案不对 | 检索问题 → [RAG 与检索](/rag/) |
| 模型不调用你写的工具，或者反复调同一个 | 工具 `description` 没写清「什么时候用」→ [工具描述太模糊模型不调用](/agent/工具描述太模糊模型不调用) |
| 模型返回的内容解析不出来 | 输出格式没约束住 → [结构化输出解析失败](/python/结构化输出解析失败) |
| 中文变乱码 | 编码 → [流式输出中文乱码](/env/流式输出中文乱码) |

## 和别处有什么不同

大部分教程教你「怎么写对」，这里只记「怎么写错、以及为什么错」。

| | |
| --- | --- |
| **Java 优先** | 市面上的 AI 踩坑几乎全是 Python 的。这里优先记录 Java 生态（Spring AI / LangChain4j / Spring Boot 3）的问题 |
| **按报错索引** | 你手上只有报错，没有章节号。所以文件名就是症状，索引表就是搜索入口 |
| **四段固定结构** | 报错原文 / 为什么 / 怎么改 / 怎么预防。看完知道怎么修，也知道下次怎么躲 |
| **标了环境版本** | 大部分坑的根因是版本差异。不标版本的坑等于没写 |
| **配报错截图** | 不用读文字，扫一眼截图就知道是不是同一个错 |

## 环境

**Java 侧**（新增条目主要在这套环境下复现）：

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| Spring Boot | 3.x |
| Spring AI | 1.x |
| LangChain4j | 1.x |
| 构建工具 | Maven 3.9+ |
| 用的模型 | DeepSeek / 阿里云百炼 qwen 系列 |

**Python 侧**（早期条目）：

| 组件 | 版本 |
| --- | --- |
| Python | 3.13 |
| langchain / langchain-core | 1.x |
| pydantic | v2 |
| chromadb | 0.5.x |

> 两侧相同的环境：Windows 11（中文版）。
> Spring AI 与 LangChain4j 都还在快速迭代，版本号以官方最新文档为准 —— 这里只标大版本。
> **同样的坑在不同版本上表现可能不同。** 你在别的版本上遇到不一样的报错，欢迎开 Issue 补充。

## 贡献

见 [CONTRIBUTING.md](https://github.com/pgy763/llm-dev-pitfalls/blob/main/CONTRIBUTING.md)。

**最省事的方式**：提一个 [Issue](https://github.com/pgy763/llm-dev-pitfalls/issues)，把报错原文和解决过程贴上来就行，格式我来整理。你的报错原文本身就有价值 —— 因为别人会搜同一句话。