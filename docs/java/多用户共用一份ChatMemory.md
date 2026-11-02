# 多用户共用一份 `ChatMemory`：回答里出现别人的提问

![报错原文](/images/java-chatmemory-shared.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

**这一条没有异常。** 它是静默错误，靠账单和用户投诉才发现的，三个典型现象：

1. **用户 A 的回答里带出用户 B 几分钟前提的问题。** 越像「昨天谁问过什么」这类自然衔接，越容易复现。
2. **同一个人的对话越长，`prompt_tokens` 只涨不落。** 第一轮 800，第十轮 9000 以上，输入 token 变成账单大头 —— 而且合并计算时，两个用户的有效上下文各自只剩一半。
3. **单人自测永远正常。** Postman 连点、`@SpringBootTest` 单线程跑，全绿；一上并发、两个账号同时用才露。

日志侧的判据：把某个 `request_id` 的 `prompt_tokens` 折算成「历史轮数 × 单轮长度」，会发现**对不上，且超出的部分带着别人的内容**。看到这个组合，先查 `ChatMemory` 的作用域，别去怀疑模型。

如果配的是 `chatMemoryProvider` 但接口方法上没有 `@MemoryId`，情况会好一点 —— 它在创建代理时就失败，不会等到线上。抛的是配置检查类异常（`IllegalConfigurationException` 这一族，**类名和措辞随版本而异**），认准异常信息里出现 `@MemoryId` 就够了。

## 为什么

```java
// 错法：单例 Assistant + 单例 ChatMemory
@Bean
Assistant assistant(ChatLanguageModel model) {
    return AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(20))   // 应用级唯一一份
        .build();
}
```

`AiServices.builder(...).build()` 出来的是一个**长生命周期对象** —— 你放在 `@Bean` 里，它就跟应用同生共死。往里塞一个 `ChatMemory` 实例，等于整个进程只有一份聊天记录。

`MessageWindowChatMemory` 只做一件事：保留最近 N 条消息，超了淘汰最早的。**它不知道这些消息属于谁** —— 没有用户维度，也没有会话维度。于是：

- 第二个用户进来，`AiServices` 把这份历史当成「当前对话」，一起塞进 prompt
- 窗口满了以后，淘汰的是最早的几条，可能是 A 的，也可能是 B 的，取决于谁先说话
- token 是所有人共享一个窗口一起烧，不是各自一份

最贵的一层在最后：`maxMessages(20)` 是**条数**不是 token。二十条长回答轻松几万 token，你以为限制了 20 条很保守，实际每次调用都在为一个共享的大 prompt 付钱。

## 怎么改

### 按会话隔离：`chatMemoryProvider` + `@MemoryId`

```java
interface Assistant {
    String chat(@MemoryId String userId, @UserMessage String message);
}

@Bean
Assistant assistant(ChatLanguageModel model) {
    return AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
            .id(id)                 // 每个 id 一份独立内存
            .maxMessages(20)
            .build())
        .build();
}
```

`chatMemoryProvider` 是「每次调用按 id 取内存」：同一个 `userId` 复用同一份，不同 `userId` 完全隔离。`@MemoryId` 标在哪个参数上，那个参数就是会话标识 —— **隔离全靠它，漏标等于没隔离**。

三个要点：

- `chatMemory(...)` 和 `chatMemoryProvider(...)` **二选一**，别两个都写。都写的时候谁生效不去赌，也说不清意图。
- 0.3x 线是 `withMaxMessages(n)` 静态工厂，1.x 改成 builder 形式（`.id()` / `.maxMessages()`）。以官方最新文档为准，别照抄半年前的博客。
- 要把 `@MemoryId` 传成什么，取决于隔离粒度：按用户隔离用 `userId`，按「用户 + 会话」隔离用 `userId + ":" + sessionId`。只按用户隔离，用户开两个窗口对话会互相污染。

### 多实例部署：默认内存实现会丢上下文

`MessageWindowChatMemory` 默认把消息存在**进程内**的 map 里。部署两台之后，同一个用户的两次请求落到不同机器，各自只有一半历史 —— 现象是「聊着聊着它突然忘了刚才说什么」，随机发生，重启后全丢。

两条路，选一条：

- **会话粘滞**：网关按 `userId` 做一致性哈希。成本低，但扩容和发版时照样丢（实例被摘掉，内存跟着没）。适合上下文丢了也能忍的场景。
- **换持久化 `ChatMemoryStore`**：LangChain4j 提供基于 JDBC / Redis 的 store 实现（坐标以官方文档为准），或者自己实现三个方法。这才是多实例该走的路。

### 只读场景别加 memory

意图分类、字段抽取、翻译 —— 这类调用**不需要历史**，每次输入都是完整的。加了 memory 等于给每次请求塞一段无关历史：成本翻倍、延迟上升，还可能让模型被前文带偏（上一句是「帮我退款」，这一句分类「查物流」它也往售后里归）。

同理，`TokenWindowChatMemory` + `TokenCountEstimator` 才是按 token 限额的做法，按条数限额在长回答场景下估不准。

## 怎么预防

**第一条：单例 `@Bean` 里出现 `.chatMemory(` 就当 code review 红线。** 这个词出现在单例配置里，九成是漏了 provider。

**第二条：用假模型写一条隔离测试，比真调省钱且稳定。**

```java
@Test
void memoryIsIsolatedPerUser() {
    // 假模型只做一件事：记录收到的每条消息，返回固定文本
    var seen = new ArrayList<List<ChatMessage>>();
    ChatLanguageModel fake = messages -> { seen.add(messages); return AiMessage.from("ok"); };

    Assistant a = buildAssistant(fake);
    a.chat("alice", "我的订单号是 A123");
    a.chat("bob", "我的问题是什么");

    String bobPrompt = seen.get(1).toString();
    assertFalse(bobPrompt.contains("A123"), "Bob 的 prompt 里出现了 Alice 的内容");
}
```

断言写「不含对方的关键词」，而不是「消息条数等于 1」—— 后者会被 system message 之类的无关变化弄成假绿。

**第三条：监控 `prompt_tokens / completion_tokens` 的比值。** 正常的多轮对话在 2–5 之间波动；这个比值随并发用户数**线性上涨**，基本就是共享了内存。把它做成一个面板指标，比等投诉快。

**第四条：把「会话标识从哪来」写进接口签名，而不是从 `ThreadLocal` 里摸。** 用 `@MemoryId` 显式传，和虚拟密钥那套 `AccessContext` 是同一个道理：隐式上下文在换线程、异步回调里失效时不出声。

> 静态检查能直接指出这类共享：`llm-lint` 的 [LLM002](https://github.com/pgy763/llm-lint/blob/main/docs/rules.md#llm002) 找「按类型注入 `ChatMemory`、多个用户共用一个实例」，扫一遍全仓库就知道有没有。

## 环境

| 组件 | 版本 |
| --- | --- |
| LangChain4j | 1.x（`chatMemoryProvider` / `@MemoryId` 在 0.3x 已存在，`ChatMemory` 的创建方式以官方文档为准） |
| JDK | 17+ |
| Spring Boot | 3.x（用 `langchain4j-spring-boot-starter` 时同理，根因是 Bean 的作用域） |
| 复现条件 | 单例 `Assistant` + `chatMemory(...)` 传一个实例；两个账号交替提问 |
